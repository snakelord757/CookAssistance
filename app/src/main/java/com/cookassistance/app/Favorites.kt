package com.cookassistance.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "favorites")
data class Favorite(@PrimaryKey val id: String, val snapshot: String)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY id") fun observe(): Flow<List<Favorite>>
    @Query("SELECT * FROM favorites WHERE id = :id") suspend fun find(id: String): Favorite?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(favorite: Favorite)
    @Query("DELETE FROM favorites WHERE id = :id") suspend fun remove(id: String)
}

@Database(entities = [Favorite::class], version = 1, exportSchema = true)
abstract class CookDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
}

interface FavoriteStore {
    fun observe(): Flow<List<Recipe>>
    suspend fun find(id: String): Recipe?
    suspend fun save(recipe: Recipe)
    suspend fun remove(id: String)
}

class RoomFavoriteStore(private val dao: FavoriteDao) : FavoriteStore {
    override fun observe(): Flow<List<Recipe>> =
        dao.observe().map { rows -> rows.map { recipeJson.decodeFromString<Recipe>(it.snapshot) } }

    override suspend fun find(id: String): Recipe? =
        dao.find(id)?.let { recipeJson.decodeFromString<Recipe>(it.snapshot) }

    override suspend fun save(recipe: Recipe) = dao.save(Favorite(recipe.id, recipeJson.encodeToString(recipe)))

    override suspend fun remove(id: String) = dao.remove(id)
}
