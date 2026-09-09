package com.elicode.app.github

import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal GitHub REST client over HttpURLConnection (no extra deps).
 * Tokens are passed per call and never persisted here — persistence is
 * the job of KeystoreStore.
 */
class GitHubApi(private val gson: Gson = Gson()) {

    data class User(val login: String, val name: String?, @SerializedName("avatar_url") val avatarUrl: String?)
    data class Repo(
        val name: String,
        @SerializedName("full_name") val fullName: String,
        @SerializedName("clone_url") val cloneUrl: String,
        @SerializedName("ssh_url") val sshUrl: String,
        val private: Boolean,
        val description: String?,
        val language: String?,
        @SerializedName("default_branch") val defaultBranch: String?,
        @SerializedName("updated_at") val updatedAt: String?
    )
    data class SearchResult(@SerializedName("items") val items: List<Repo>)

    private fun get(path: String, token: String?): String {
        val conn = (URL("https://api.github.com$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "EliCode/0.1")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) {
            val msg = runCatching { conn.errorStream?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
            val rate = "rate limit" in msg.lowercase() || "api rate limit" in msg.lowercase()
            throw AuthException(
                if (rate && token.isNullOrBlank()) "GitHub rate limit reached for anonymous requests."
                else "GitHub rejected the credentials (HTTP $code).",
                rate
            )
        }
        if (code == 404) throw FileNotFoundException("Not found: $path")
        if (code !in 200..299) {
            throw java.io.IOException("GitHub HTTP $code for $path")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    class AuthException(message: String, val rateLimited: Boolean) : java.io.IOException(message)

    private fun <T> call(op: String, block: () -> T): EliResult<T> {
        return try {
            EliResult.Ok(block())
        } catch (e: AuthException) {
            EliResult.Err(
                EliError(
                    operation = op, message = e.message.orEmpty(),
                    probableCause = if (e.rateLimited) "Too many anonymous requests." else "Invalid or expired token.",
                    suggestedFix = if (e.rateLimited) "Connect your GitHub account to raise the rate limit."
                    else "Reconnect your GitHub account in the GitHub tab."
                )
            )
        } catch (e: FileNotFoundException) {
            EliResult.Err(EliError(op, message = e.message.orEmpty(),
                probableCause = "Repository or user does not exist (or is private).",
                suggestedFix = "Check the name, or connect an account with access."))
        } catch (t: Throwable) {
            EliResult.Err(
                EliError(op, message = "${t.javaClass.simpleName}: ${t.message}",
                    probableCause = "Network problem or GitHub outage.",
                    suggestedFix = "Check connectivity and retry.")
            )
        }
    }

    fun me(token: String): EliResult<User> = call("GitHub login") {
        gson.fromJson(get("/user", token), User::class.java)
    }

    fun myRepos(token: String, page: Int = 1): EliResult<List<Repo>> = call("List repositories") {
        val body = get("/user/repos?per_page=100&page=$page&sort=updated", token)
        gson.fromJson(body, Array<Repo>::class.java).toList()
    }

    fun search(query: String, token: String?): EliResult<List<Repo>> = call("Search repositories") {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = get("/search/repositories?q=$q&per_page=30", token)
        gson.fromJson(body, SearchResult::class.java).items
    }

    fun repo(fullName: String, token: String?): EliResult<Repo> = call("Open repository") {
        gson.fromJson(get("/repos/$fullName", token), Repo::class.java)
    }
}
