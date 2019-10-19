/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gradlenexus.publishplugin.internal

import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.time.Duration

class NexusClient(private val baseUrl: URI, username: String?, password: String?, timeout: Duration?, connectTimeout: Duration?) {
    private val api: NexusApi

    init {
        val httpClientBuilder = OkHttpClient.Builder()
        if (timeout != null) {
            httpClientBuilder
                    .readTimeout(timeout)
                    .writeTimeout(timeout)
        }
        if (connectTimeout != null) {
            httpClientBuilder.connectTimeout(connectTimeout)
        }
        if (username != null || password != null) {
            val credentials = Credentials.basic(username ?: "", password ?: "")
            httpClientBuilder
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder()
                                .header("Authorization", credentials)
                                .build())
                    }
        }
        val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl.toString())
                .client(httpClientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        api = retrofit.create(NexusApi::class.java)
    }

    fun findStagingProfileId(packageGroup: String): String? {
        val response = api.stagingProfiles.execute()
        if (!response.isSuccessful) {
            throw failure("load staging profiles", response)
        }
        return response.body()
                ?.data
                ?.filter { profile ->
                    // profile.name either matches exactly
                    // or it is a prefix of a packageGroup
                    packageGroup.startsWith(profile.name) &&
                            (packageGroup.length == profile.name.length ||
                                    packageGroup[profile.name.length] == '.')
                }
                ?.maxBy { it.name.length }
                ?.id
    }

    fun createStagingRepository(stagingProfileId: String): String {
        val response = api.startStagingRepo(stagingProfileId, Dto(Description("Created by io.github.gradle-nexus.publish-plugin Gradle plugin"))).execute()
        if (!response.isSuccessful) {
            throw failure("create staging repository", response)
        }
        return response.body()?.data?.stagedRepositoryId ?: throw RuntimeException("No response body")
    }

    fun closeStagingRepository(stagingRepositoryId: String) {
        val response = api.closeStagingRepo(Dto(StagingRepositoryToTransit(listOf(stagingRepositoryId), "Closed by io.github.gradle-nexus.publish-plugin Gradle plugin"))).execute()
        if (!response.isSuccessful) {
            throw failure("close staging repository", response)
        }
    }

    fun releaseStagingRepository(stagingRepositoryId: String) {
        val response = api.releaseStagingRepo(Dto(StagingRepositoryToTransit(listOf(stagingRepositoryId), "Release by io.github.gradle-nexus.publish-plugin Gradle plugin"))).execute()
        if (!response.isSuccessful) {
            throw failure("release staging repository", response)
        }
    }

    fun getStagingRepositoryUri(stagingRepositoryId: String): URI =
            URI.create("${baseUrl.toString().removeSuffix("/")}/staging/deployByRepositoryId/$stagingRepositoryId")

    // TODO: Cover all API calls with unified error handling (including unexpected IOExceptions)
    private fun failure(action: String, response: Response<*>): RuntimeException {
        var message = "Failed to " + action + ", server responded with status code " + response.code()
        val errorBody = response.errorBody()
        if (errorBody != null && errorBody.contentLength() > 0) {
            try {
                message += ", body: " + errorBody.string()
            } catch (e: IOException) {
                throw UncheckedIOException("Failed to read body of error response", e)
            }
        }
        return RuntimeException(message)
    }

    private interface NexusApi {

        companion object {
            private const val RELEASE_OPERATION_NAME_IN_NEXUS = "promote" // promote and release use the same operation, used body parameters matter
        }

        @get:Headers("Accept: application/json")
        @get:GET("staging/profiles")
        val stagingProfiles: Call<Dto<List<StagingProfile>>>

        @Headers("Content-Type: application/json")
        @POST("staging/profiles/{stagingProfileId}/start")
        fun startStagingRepo(@Path("stagingProfileId") stagingProfileId: String, @Body description: Dto<Description>): Call<Dto<StagingRepository>>

        @Headers("Content-Type: application/json")
        @POST("staging/bulk/close")
        fun closeStagingRepo(@Body stagingRepositoryToClose: Dto<StagingRepositoryToTransit>): Call<Unit>

        @Headers("Content-Type: application/json")
        @POST("staging/bulk/$RELEASE_OPERATION_NAME_IN_NEXUS")
        fun releaseStagingRepo(@Body stagingRepositoryToClose: Dto<StagingRepositoryToTransit>): Call<Unit>
    }

    data class Dto<T>(var data: T)

    data class StagingProfile(var id: String, var name: String)

    data class Description(val description: String)

    data class StagingRepository(var stagedRepositoryId: String)

    data class StagingRepositoryToTransit(val stagedRepositoryIds: List<String>, val description: String, val autoDropAfterRelease: Boolean = true)
}
