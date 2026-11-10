package com.cookassistance.app

import android.app.Application
import androidx.room.Room

class CookApplication : Application() {
    private val http by lazy { httpClient() }
    private val db by lazy { Room.databaseBuilder(this, CookDatabase::class.java, "cook.db").build() }
    private val store by lazy { RoomFavoriteStore(db.favorites()) }
    val recipes: RecipeRepository by lazy { RecipeRepositoryImpl(http, BuildConfig.SERVER_URL, store) }
    val assistant: Assistant by lazy { AssistantRepository(http, BuildConfig.SERVER_URL) }
    val feedback: FeedbackRepository by lazy { NetworkFeedbackRepository(http, BuildConfig.SERVER_URL) }
}
