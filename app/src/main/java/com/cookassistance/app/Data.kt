package com.cookassistance.app

import android.app.Application
import androidx.room.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Recipe(val id: Int, val name: String, val ingredients: List<String>, val instructions: List<String>,
    val image: String = "", val prepTimeMinutes: Int = 0, val cookTimeMinutes: Int = 0, val servings: Int = 0)
@Serializable data class RecipePage(val recipes: List<Recipe>)
@Serializable data class AssistRequest(val recipeId: Int, val question: String)
@Serializable data class AssistReply(val answer: String = "", val ignored: Boolean = false)

val recipeJson = Json { ignoreUnknownKeys = true }
fun recipeHttpClient() = HttpClient(OkHttp) {
    expectSuccess = true
    install(ContentNegotiation) { json(recipeJson) }
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
}

@Entity(tableName = "favorites") data class Favorite(@PrimaryKey val id: Int, val snapshot: String)
@Dao interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY id") fun observe(): Flow<List<Favorite>>
    @Query("SELECT * FROM favorites WHERE id = :id") suspend fun find(id: Int): Favorite?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(favorite: Favorite)
    @Query("DELETE FROM favorites WHERE id = :id") suspend fun remove(id: Int)
}
@Database(entities = [Favorite::class], version = 1, exportSchema = true)
abstract class CookDatabase : RoomDatabase() { abstract fun favorites(): FavoriteDao }

interface Recipes {
    val favorites: Flow<List<Recipe>>
    suspend fun list(): List<Recipe>
    suspend fun get(id: Int): Recipe
    suspend fun save(recipe: Recipe)
    suspend fun remove(id: Int)
}
class RecipeRepository(private val http: HttpClient, private val dao: FavoriteDao) : Recipes {
    override val favorites = dao.observe().map { rows -> rows.map { recipeJson.decodeFromString<Recipe>(it.snapshot) } }
    override suspend fun list(): List<Recipe> = http.get("https://dummyjson.com/recipes?limit=0").body<RecipePage>().recipes
    override suspend fun get(id: Int): Recipe = dao.find(id)?.let { recipeJson.decodeFromString<Recipe>(it.snapshot) }
        ?: http.get("https://dummyjson.com/recipes/$id").body()
    override suspend fun save(recipe: Recipe) = dao.save(Favorite(recipe.id, recipeJson.encodeToString(recipe)))
    override suspend fun remove(id: Int) = dao.remove(id)
}
interface Assistant { suspend fun ask(id: Int, question: String): AssistReply }
class AssistantRepository(private val http: HttpClient, private val endpoint: String) : Assistant {
    override suspend fun ask(id: Int, question: String): AssistReply {
        check(endpoint.isNotBlank()) { "Assistant endpoint not configured" }
        return http.post("${endpoint.trimEnd('/')}/assist") { contentType(ContentType.Application.Json); setBody(AssistRequest(id, question)) }.body()
    }
}
class CookApplication : Application() {
    private val http by lazy { recipeHttpClient() }
    private val db by lazy { Room.databaseBuilder(this, CookDatabase::class.java, "cook.db").build() }
    val recipes: Recipes by lazy { RecipeRepository(http, db.favorites()) }
    val assistant: Assistant by lazy { AssistantRepository(http, BuildConfig.ASSIST_URL) }
}
