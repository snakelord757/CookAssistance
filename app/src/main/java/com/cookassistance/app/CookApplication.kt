package com.cookassistance.app

import android.app.Application

class CookApplication : Application() {
    private val http by lazy { httpClient() }
    val recipes: RecipeRepository by lazy { NetworkRecipeRepository(http, BuildConfig.SERVER_URL) }
    val assistant: Assistant by lazy { AssistantRepository(http, BuildConfig.SERVER_URL) }
}
