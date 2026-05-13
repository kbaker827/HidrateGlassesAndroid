package com.hidrateglasses.data.api

import com.google.gson.annotations.SerializedName
import com.hidrateglasses.data.models.DrinkEvent
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// ---------------------------------------------------------------------------
// Request / response DTOs
// ---------------------------------------------------------------------------

data class TokenRequest(
    @SerializedName("grant_type") val grantType: String = "password",
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("client_id") val clientId: String,
    @SerializedName("client_secret") val clientSecret: String
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String = "",
    @SerializedName("token_type") val tokenType: String = "Bearer",
    @SerializedName("expires_in") val expiresIn: Long = 3600L
)

data class RefreshTokenRequest(
    @SerializedName("grant_type") val grantType: String = "refresh_token",
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("client_id") val clientId: String,
    @SerializedName("client_secret") val clientSecret: String
)

data class UserProfile(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("avatar_url") val avatarUrl: String? = null
)

data class HydrationGoal(
    @SerializedName("id") val id: String,
    @SerializedName("daily_goal_oz") val dailyGoalOz: Float,
    @SerializedName("unit") val unit: String = "oz",
    @SerializedName("updated_at") val updatedAt: Long = 0L
)

data class HydrationEventsResponse(
    @SerializedName("events") val events: List<DrinkEvent>,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("page") val page: Int = 1
)

// ---------------------------------------------------------------------------
// Retrofit interface
// ---------------------------------------------------------------------------

interface HidrateApiService {

    /**
     * Exchange username+password (or refresh_token) for an access token.
     * POST /auth/token
     */
    @POST("auth/token")
    suspend fun getToken(@Body request: TokenRequest): Response<TokenResponse>

    /**
     * Refresh an existing access token.
     * POST /auth/token (grant_type=refresh_token)
     */
    @POST("auth/token")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<TokenResponse>

    /**
     * Fetch the authenticated user's profile.
     * GET /user/me
     */
    @GET("user/me")
    suspend fun getUserProfile(): Response<UserProfile>

    /**
     * Retrieve paginated hydration events.
     * GET /hydration_events
     *
     * @param startDate  ISO-8601 date string (inclusive lower bound), e.g. "2024-01-01"
     * @param endDate    ISO-8601 date string (inclusive upper bound)
     * @param page       1-based page number
     * @param pageSize   Number of results per page (max 200)
     */
    @GET("hydration_events")
    suspend fun getHydrationEvents(
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100
    ): Response<HydrationEventsResponse>

    /**
     * Retrieve the user's current daily hydration goal.
     * GET /goals/current
     */
    @GET("goals/current")
    suspend fun getCurrentGoal(): Response<HydrationGoal>
}
