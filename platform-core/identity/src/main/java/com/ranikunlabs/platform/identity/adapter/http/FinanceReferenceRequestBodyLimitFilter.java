package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.adapter.http.dto.response.ApiResponse;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Strict, bounded JSON parsing for the reference-only credential entry route. */
final class FinanceReferenceRequestBodyLimitFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_BYTES = 2048;
    private static final int MAX_USER_ID_BYTES = 128;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final JsonFactory STRICT_JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final ObjectMapper objectMapper;
    private final String path;
    private final String identityField;
    private final int maxIdentityBytes;

    FinanceReferenceRequestBodyLimitFilter(ObjectMapper objectMapper) {
        this(objectMapper, FinanceReferenceAuthController.PATH, "userId", MAX_USER_ID_BYTES);
    }

    /** Exact-shape guard for a credential route: exactly one identity field plus {@code password}. */
    FinanceReferenceRequestBodyLimitFilter(
            ObjectMapper objectMapper,
            String path,
            String identityField,
            int maxIdentityBytes
    ) {
        this.objectMapper = objectMapper;
        this.path = path;
        this.identityField = identityField;
        this.maxIdentityBytes = maxIdentityBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        boolean exactPath = path.equals(request.getServletPath())
                || path.equals(requestUri)
                || (requestUri != null && requestUri.endsWith(path));
        return !"POST".equalsIgnoreCase(request.getMethod()) || !exactPath;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            jakarta.servlet.FilterChain filterChain
    ) throws IOException, jakarta.servlet.ServletException {
        if (!isJson(request.getContentType()) || request.getContentLengthLong() > MAX_BODY_BYTES) {
            writeBadRequest(response);
            return;
        }

        byte[] body = readBounded(request.getInputStream());
        if (body == null || !hasExactRequestShape(body)) {
            writeBadRequest(response);
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static boolean isJson(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (org.springframework.http.InvalidMediaTypeException exception) {
            return false;
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (buffer.size() + count > MAX_BODY_BYTES) {
                return null;
            }
            buffer.write(chunk, 0, count);
        }
        return buffer.toByteArray();
    }

    private boolean hasExactRequestShape(byte[] body) {
        try (JsonParser parser = STRICT_JSON_FACTORY.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            Set<String> fields = new HashSet<>();
            String userId = null;
            String password = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                    return false;
                }
                String field = parser.currentName();
                if (!fields.add(field)) {
                    return false;
                }
                if (identityField.equals(field)) {
                    if (parser.nextToken() != JsonToken.VALUE_STRING) {
                        return false;
                    }
                    userId = parser.getValueAsString();
                } else if ("password".equals(field)) {
                    if (parser.nextToken() != JsonToken.VALUE_STRING) {
                        return false;
                    }
                    password = parser.getValueAsString();
                } else {
                    return false;
                }
            }
            if (parser.nextToken() != null || fields.size() != 2
                    || userId == null || userId.isBlank()
                    || password == null || password.isBlank()) {
                return false;
            }
            return userId.getBytes(StandardCharsets.UTF_8).length <= maxIdentityBytes
                    && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void writeBadRequest(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.of(HttpStatus.BAD_REQUEST, "Invalid Finance reference request."));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Async input is not supported for this route.");
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
