package com.ranikunlabs.platform.gateway.filter

import com.ranikunlabs.platform.gateway.config.FinanceTrustedContextConfig
import com.ranikunlabs.platform.gateway.config.GatewayConfig
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val FINANCE_TRUSTED_CONTEXT_PROFILE = "finance-trusted-context-reference"

@Component
@Profile(FINANCE_TRUSTED_CONTEXT_PROFILE)
class FinanceTrustedContextGatewayFilter(
    private val gatewayConfig: GatewayConfig,
    private val environment: Environment
) : GlobalFilter, Ordered {

    init {
        FinanceTrustedContextProfileGuard.validate(gatewayConfig.financeTrustedContext, environment)
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.uri.path
        if (!FinanceTrustedContextWire.isFinanceJournalNamespace(path)) {
            return chain.filter(exchange)
        }

        if (!FinanceTrustedContextWire.isSupportedRoute(request.method, path, request.uri.rawPath)) {
            return reject(exchange, HttpStatus.FORBIDDEN)
        }

        val verifiedSubject = exchange.getAttribute<String>(VERIFIED_IDENTITY_SUBJECT_ATTRIBUTE)
            ?: return reject(exchange, HttpStatus.UNAUTHORIZED)
        val headers = FinanceTrustedContextWire.createHeaders(
            verifiedSubject,
            gatewayConfig.financeTrustedContext.serviceSecret
        ) ?: return reject(exchange, HttpStatus.UNAUTHORIZED)

        val forwarded = exchange.mutate()
            .request { builder ->
                builder.headers { values ->
                    values.keys
                        .filter { name -> name.startsWith("X-Finance-", ignoreCase = true) }
                        .forEach(values::remove)
                    listOf(
                        HttpHeaders.AUTHORIZATION,
                        HttpHeaders.COOKIE,
                        "X-User-Id",
                        "X-Organization-Id",
                        "X-Role",
                        "X-Public-Id",
                        "X-Gateway-Secret",
                        "X-Service-Id",
                        "X-Service-Secret"
                    ).forEach(values::remove)
                    headers.forEach { (name, value) -> values.set(name, value) }
                }
            }
            .build()
        return chain.filter(forwarded)
    }

    override fun getOrder(): Int = 0

    private fun reject(exchange: ServerWebExchange, status: HttpStatus): Mono<Void> {
        exchange.response.statusCode = status
        return exchange.response.setComplete()
    }
}

@Component
class FinanceTrustedContextProfileGuard(
    gatewayConfig: GatewayConfig,
    environment: Environment
) {
    init {
        validate(gatewayConfig.financeTrustedContext, environment)
    }

    companion object {
        fun validate(config: FinanceTrustedContextConfig, environment: Environment) {
            val fixtureProfileActive = environment.activeProfiles.any {
                it.equals(FINANCE_TRUSTED_CONTEXT_PROFILE, ignoreCase = true)
            }
            val productionProfileActive = environment.activeProfiles.any(::isProductionValue)
            val productionMarker = sequenceOf(
                environment.getProperty("PLATFORM_ENVIRONMENT"),
                environment.getProperty("platform.metrics.environment"),
                environment.getProperty("platform.environment")
            ).filterNotNull().any(::isProductionValue)

            require(!(fixtureProfileActive && (productionProfileActive || productionMarker))) {
                "Finance trusted-context reference profile cannot run with a production marker."
            }
            require(config.enabled == fixtureProfileActive) {
                "Finance trusted-context reference must be enabled only by its explicit fixture profile."
            }
            if (config.enabled) {
                FinanceTrustedContextWire.validateServiceSecret(config.serviceSecret)
            }
        }

        private fun isProductionValue(value: String): Boolean {
            val normalized = value.trim()
            return normalized.equals("prod", ignoreCase = true) ||
                normalized.equals("production", ignoreCase = true)
        }
    }
}

internal object FinanceTrustedContextWire {
    const val VERSION_HEADER = "X-Finance-Context-Version"
    const val SERVICE_ID_HEADER = "X-Finance-Service-Id"
    const val SERVICE_SECRET_HEADER = "X-Finance-Service-Secret"
    const val AUDIENCE_HEADER = "X-Finance-Audience"
    const val SUBJECT_HEADER = "X-Finance-Subject"

    const val VERSION = "1"
    const val SERVICE_ID = "gateway-app"
    const val AUDIENCE = "finance-journal"
    const val MAX_SUBJECT_UTF8_BYTES = 256
    const val MAX_CONTEXT_HEADER_VALUE_BYTES = 2048

    private const val JOURNALS_PATH = "/finance/journals"
    private val journalId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun isFinanceJournalNamespace(path: String): Boolean =
        path == JOURNALS_PATH || path.startsWith("$JOURNALS_PATH/")

    fun isSupportedRoute(method: HttpMethod?, path: String, rawPath: String?): Boolean {
        if (path != rawPath) return false
        return when {
            path == JOURNALS_PATH -> method == HttpMethod.GET || method == HttpMethod.POST
            path.startsWith("$JOURNALS_PATH/") && method == HttpMethod.GET ->
                journalId.matches(path.removePrefix("$JOURNALS_PATH/"))
            else -> false
        }
    }

    fun createHeaders(subject: String, serviceSecret: String): Map<String, String>? {
        val encodedSubject = encodeSubject(subject) ?: return null
        if (!isValidServiceSecret(serviceSecret)) return null
        val values = linkedMapOf(
            VERSION_HEADER to VERSION,
            SERVICE_ID_HEADER to SERVICE_ID,
            SERVICE_SECRET_HEADER to serviceSecret,
            AUDIENCE_HEADER to AUDIENCE,
            SUBJECT_HEADER to encodedSubject
        )
        val totalValueBytes = values.values.sumOf { it.toByteArray(StandardCharsets.UTF_8).size }
        return values.takeIf { totalValueBytes <= MAX_CONTEXT_HEADER_VALUE_BYTES }
    }

    fun validateServiceSecret(serviceSecret: String) {
        require(isValidServiceSecret(serviceSecret)) {
            "Finance trusted-context fixture proof must be at least 32 printable ASCII bytes without commas."
        }
        val minimumValues = VERSION.toByteArray(StandardCharsets.UTF_8).size +
            SERVICE_ID.toByteArray(StandardCharsets.UTF_8).size +
            AUDIENCE.toByteArray(StandardCharsets.UTF_8).size +
            serviceSecret.toByteArray(StandardCharsets.UTF_8).size
        require(minimumValues < MAX_CONTEXT_HEADER_VALUE_BYTES) {
            "Finance trusted-context fixture proof exceeds the supported header-value envelope."
        }
    }

    private fun isValidServiceSecret(value: String): Boolean =
        value.toByteArray(StandardCharsets.UTF_8).size >= 32 &&
            value.all { it.code in 0x21..0x7e && it != ',' }

    private fun encodeSubject(subject: String): String? {
        if (subject.isBlank() || subject.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        val encoded = try {
            val buffer: ByteBuffer = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(subject))
            ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
        } catch (_: CharacterCodingException) {
            return null
        }
        if (encoded.isEmpty() || encoded.size > MAX_SUBJECT_UTF8_BYTES) return null
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)
    }
}
