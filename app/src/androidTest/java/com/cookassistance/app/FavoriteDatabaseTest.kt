package com.cookassistance.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteDatabaseTest {
    @Test fun snapshotSurvivesDatabaseReopenAndRemoval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "favorite-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, CookDatabase::class.java, name).build()
        try {
            val recipe = Recipe(1, "Soup", listOf("Water"), listOf("Boil"))
            db.favorites().save(Favorite(recipe.id, recipeJson.encodeToString(recipe)))
            db.close()
            db = Room.databaseBuilder(context, CookDatabase::class.java, name).build()
            assertEquals(recipe, recipeJson.decodeFromString<Recipe>(db.favorites().find(1)!!.snapshot))
            db.favorites().remove(1)
            assertNull(db.favorites().find(1))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
