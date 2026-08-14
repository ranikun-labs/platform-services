package com.ranikunlabs.platform.gateway.filter

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import com.ranikunlabs.platform.gateway.config.GatewayJwtKeyFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class JwtVerifier(
    @Value("\${platform.gateway.jwt-verification-secret}") secretKey: String
) {

    private val key = GatewayJwtKeyFactory.create(secretKey)

    fun verifyAndGetClaims(token: String): Claims =
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
}
