package io.orangebuffalo.renalo.test

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.micronaut.runtime.server.EmbeddedServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ApiTestClient(
    private val server: EmbeddedServer,
) {
    private val httpClient = HttpClient.newHttpClient()

    fun login(username: String, password: String): String {
        val response = postJson(
            "/api/create-auth-token",
            """
                {"username":"$username","password":"$password"}
            """.trimIndent(),
            null,
        )

        response.statusCode().shouldBe(200)
        val token = extractToken(response.body())
        token.shouldNotBeBlank()
        return token
    }

    fun extractToken(responseBody: String): String {
        val match = tokenRegex.find(responseBody)
        (match != null).shouldBe(true)
        return match!!.groupValues[1]
    }

    fun get(path: String, token: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(server.url.toString() + path)).GET()
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun postJson(path: String, body: String, token: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(server.url.toString() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun patchJson(path: String, body: String, token: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(server.url.toString() + path))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun delete(path: String, token: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(server.url.toString() + path)).DELETE()
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val tokenRegex = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
    }
}
