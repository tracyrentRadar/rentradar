package com.rentradar.android.di;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rentradar.android.BuildConfig;
import com.rentradar.android.data.remote.AuthInterceptor;
import com.rentradar.android.data.remote.TokenAuthenticator;
import com.rentradar.android.data.remote.api.AuthApi;
import com.rentradar.android.data.remote.api.PredictionApi;
import com.rentradar.android.data.remote.api.PropertyApi;

import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Every method here is static. The class is final with a private constructor,
 * so Hilt never instantiates it, and a single non-static @Provides would break
 * the build with "Modules that need to be instantiated by Hilt must have a
 * visible, empty constructor".
 */
@Module
@InstallIn(SingletonComponent.class)
public final class NetworkModule {

    private static final String REFRESH = "refresh";

    private NetworkModule() {
    }

    @Provides
    @Singleton
    static Gson gson() {
        return new GsonBuilder().create();
    }

    @Provides
    @Singleton
    static HttpLoggingInterceptor logging() {
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        interceptor.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BODY
                : HttpLoggingInterceptor.Level.NONE);
        interceptor.redactHeader("Authorization");
        return interceptor;
    }

    // ---- the plain stack, used only to refresh ----

    @Provides
    @Singleton
    @Named(REFRESH)
    static OkHttpClient refreshClient(HttpLoggingInterceptor logging) {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();
    }

    @Provides
    @Singleton
    @Named(REFRESH)
    static Retrofit refreshRetrofit(@Named(REFRESH) OkHttpClient client, Gson gson) {
        return new Retrofit.Builder()
                .baseUrl(BuildConfig.API_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
    }

    @Provides
    @Singleton
    @Named(REFRESH)
    static AuthApi refreshApi(@Named(REFRESH) Retrofit retrofit) {
        return retrofit.create(AuthApi.class);
    }

    // ---- the authenticated stack, used for everything else ----

    @Provides
    @Singleton
    static OkHttpClient okHttpClient(AuthInterceptor auth,
                                     HttpLoggingInterceptor logging,
                                     TokenAuthenticator authenticator) {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(auth)
                .addInterceptor(logging)
                .authenticator(authenticator)
                .build();
    }

    @Provides
    @Singleton
    static Retrofit retrofit(OkHttpClient client, Gson gson) {
        return new Retrofit.Builder()
                .baseUrl(BuildConfig.API_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
    }

    @Provides
    @Singleton
    static AuthApi authApi(Retrofit retrofit) {
        return retrofit.create(AuthApi.class);
    }

    @Provides
    @Singleton
    static PropertyApi propertyApi(Retrofit retrofit) {
        return retrofit.create(PropertyApi.class);
    }

    @Provides
    @Singleton
    static PredictionApi predictionApi(Retrofit retrofit) {
        return retrofit.create(PredictionApi.class);
    }
}