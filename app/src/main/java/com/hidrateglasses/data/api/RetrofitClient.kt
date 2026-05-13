package com.hidrateglasses.data.api

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hidrateglasses.BuildConfig
import com.hidrateglasses.data.repository.PreferencesKeys
import com.hidrateglasses.data.repository.dataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private const val BASE_URL = "https://api.hidratespark.com/v1/"

/**
 * OkHttp [Authenticator] that transparently refreshes the OAuth2 access token
 * when the server returns 401 and retries the original request.
 */
class TokenAuthenticator(
    private val context: Context
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite retry loops
        if (response.request.header("X-Token-Refreshed") != null) return null

        val refreshToken = runBlocking {
            context.dataStore.data.firstOrNull()
                ?.get(PreferencesKeys.REFRESH_TOKEN)
        } ?: return null

        val refreshResponse = runBlocking {
            try {
                // Build a minimal retrofit instance without the authenticator to avoid recursion
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val api = Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(HidrateApiService::class.java)
                api.refreshToken(
                    RefreshTokenRequest(
                        refreshToken = refreshToken,
                        clientId = BuildConfig.HIDRATE_CLIENT_ID,
                        clientSecret = BuildConfig.HIDRATE_CLIENT_SECRET
                    )
                )
            } catch (e: Exception) {
                null
            }
        } ?: return null

        if (!refreshResponse.isSuccessful) return null
        val newToken = refreshResponse.body()?.accessToken ?: return null

        // Persist the new token
        runBlocking {
            context.dataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    set(PreferencesKeys.ACCESS_TOKEN, newToken)
                    refreshResponse.body()?.refreshToken
                        ?.takeIf { it.isNotBlank() }
                        ?.let { set(PreferencesKeys.REFRESH_TOKEN, it) }
                }
            }
        }

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header("X-Token-Refreshed", "true")
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                // Attach Bearer token from DataStore if available
                val token = runBlocking {
                    context.dataStore.data.firstOrNull()
                        ?.get(PreferencesKeys.ACCESS_TOKEN)
                }
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .authenticator(TokenAuthenticator(context))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideHidrateApiService(retrofit: Retrofit): HidrateApiService =
        retrofit.create(HidrateApiService::class.java)
}
