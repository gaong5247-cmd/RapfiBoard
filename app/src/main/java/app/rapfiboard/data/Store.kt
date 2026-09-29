package app.rapfiboard.data

import android.content.Context
import androidx.room.*
import app.rapfiboard.core.*
import app.rapfiboard.engine.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName="games", indices=[Index(value=["fingerprint"], unique=true)])
data class GameEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val title:String,val notation:String,val fingerprint:String,val result:Int=-1,val created:Long=System.currentTimeMillis(),val favorite:Boolean=false,val comment:String="")
@Entity(tableName="positions",primaryKeys=["gameId","ply"],indices=[Index("positionKey")])
data class PositionEntity(val gameId:Long,val ply:Int,val positionKey:String,val x:Int,val y:Int,val side:Int,val result:Int)
@Entity(tableName="analysis_cache")
data class CacheEntity(@PrimaryKey val key:String,val json:String,val created:Long=System.currentTimeMillis())
@Entity(tableName="reviews",primaryKeys=["gameKey","configKey","ply"])
data class ReviewEntity(val gameKey:String,val configKey:String,val ply:Int,val json:String)
@Entity(tableName="bookmarks")
data class BookmarkEntity(@PrimaryKey val key:String,val notation:String,val comment:String,val created:Long=System.currentTimeMillis())
@Entity(tableName="benchmarks")
data class BenchmarkEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val device:String,val evaluator:String,val threads:Int,val nodes:Long,val elapsed:Long,val nps:Long,val created:Long=System.currentTimeMillis())
data class MoveStat(val x:Int,val y:Int,val games:Int,val wins:Int,val draws:Int,val losses:Int)
@Dao interface StoreDao {
    @Query("SELECT * FROM games WHERE title LIKE '%' || :query || '%' ORDER BY favorite DESC, created DESC") fun games(query:String=""):Flow<List<GameEntity>>
    @Query("SELECT * FROM games ORDER BY id") suspend fun allGames():List<GameEntity>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertGame(game:GameEntity):Long
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertPositions(positions:List<PositionEntity>)
    @Query("SELECT x,y,COUNT(*) AS games,SUM(CASE WHEN result=side THEN 1 ELSE 0 END) AS wins,SUM(CASE WHEN result=0 THEN 1 ELSE 0 END) AS draws,SUM(CASE WHEN result>0 AND result!=side THEN 1 ELSE 0 END) AS losses FROM positions WHERE positionKey=:key GROUP BY x,y ORDER BY games DESC") suspend fun stats(key:String):List<MoveStat>
    @Query("SELECT DISTINCT games.* FROM games INNER JOIN positions ON games.id=positions.gameId WHERE positions.positionKey=:key") suspend fun positionGames(key:String):List<GameEntity>
    @Update suspend fun updateGame(game:GameEntity)
    @Query("SELECT * FROM analysis_cache WHERE `key`=:key") suspend fun cache(key:String):CacheEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putCache(item:CacheEntity)
    @Query("DELETE FROM analysis_cache") suspend fun clearCache()
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putReview(item:ReviewEntity)
    @Query("SELECT * FROM reviews WHERE gameKey=:gameKey AND configKey=:configKey ORDER BY ply") suspend fun reviews(gameKey:String,configKey:String):List<ReviewEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun bookmark(item:BookmarkEntity)
    @Query("SELECT * FROM bookmarks ORDER BY created DESC") fun bookmarks():Flow<List<BookmarkEntity>>
    @Insert suspend fun benchmark(item:BenchmarkEntity)
    @Query("SELECT * FROM benchmarks ORDER BY created DESC LIMIT 20") fun benchmarks():Flow<List<BenchmarkEntity>>
}
@Database(entities=[GameEntity::class,PositionEntity::class,CacheEntity::class,ReviewEntity::class,BookmarkEntity::class,BenchmarkEntity::class],version=1,exportSchema=true)
abstract class AppDatabase:RoomDatabase() {
    abstract fun dao():StoreDao
    companion object { fun open(context:Context)=Room.databaseBuilder(context,AppDatabase::class.java,"rapfiboard.db").build() }
}
class DatabaseRepository(private val db:AppDatabase) {
    val dao=db.dao()
    suspend fun save(position:Position,title:String,result:Int=-1):Boolean = db.withTransaction {
        val id=dao.insertGame(GameEntity(title=title,notation=position.encode(),fingerprint=sha256(position.encode()),result=result))
        if(id==-1L) return@withTransaction false
        dao.insertPositions(position.stones.mapIndexed { i,s -> PositionEntity(id,i,position.prefix(i).key(),s.move.x,s.move.y,s.color,result) }); true
    }
    suspend fun export():String = JSONObject().put("format","RapfiBoardDatabase1").put("games",JSONArray(dao.allGames().map {
        JSONObject().put("title",it.title).put("notation",it.notation).put("result",it.result).put("comment",it.comment)
    })).toString(2)
    suspend fun import(text:String):Int {
        require(text.toByteArray().size<=20_000_000) { "가져오기 파일은 20MB 이하여야 해요." }
        if(text.trim().startsWith("RBF1|")) return if(save(Position.decode(text),"Imported game")) 1 else 0
        val root=JSONObject(text); require(root.getString("format")=="RapfiBoardDatabase1") { "지원하지 않는 데이터 형식이에요." }
        val games=root.getJSONArray("games"); require(games.length()<=10000)
        val validated=(0 until games.length()).map { val g=games.getJSONObject(it); Triple(Position.decode(g.getString("notation")),g.getString("title").take(200),g.getInt("result").also { r -> require(r in -1..2) }) }
        return db.withTransaction { validated.count { (p,t,r) -> save(p,t,r) } }
    }
}
object AnalysisCodec {
    private fun moves(ms:List<Move>)=JSONArray(ms.map { it.wire() })
    fun encode(a:Analysis):String = JSONObject().apply {
        put("key",a.positionKey); put("engine",JSONObject().put("id",a.engine.id).put("classical",a.engine.classical).put("network",a.engine.network))
        put("config",JSONObject().put("time",a.config.timeMs).put("nodes",a.config.nodes).put("depth",a.config.depth).put("pv",a.config.multiPv).put("threads",a.config.threads).put("hash",a.config.hashMb))
        put("best",a.best?.wire()); put("forbidden",moves(a.forbidden.toList()))
        put("candidates",JSONArray(a.candidates.map { c -> JSONObject().put("i",c.index).put("score",c.score.raw).put("w",c.winRate).put("d",c.drawRate).put("depth",c.depth).put("nodes",c.nodes).put("nps",c.nps).put("ms",c.timeMs).put("pv",moves(c.pv)) }))
    }.toString()
    fun decode(s:String):Analysis {
        val o=JSONObject(s); val e=o.getJSONObject("engine"); val cfg=o.getJSONObject("config")
        fun parse(a:JSONArray)=(0 until a.length()).map { Move.parse(a.getString(it),22) ?: error("손상된 캐시") }
        val cs=o.getJSONArray("candidates")
        return Analysis(o.getString("key"),EngineSpec(id=e.getString("id"),classical=e.getBoolean("classical"),network=e.getString("network")),AnalysisConfig(cfg.getLong("time"),cfg.getLong("nodes"),cfg.getInt("depth"),cfg.getInt("pv"),cfg.getInt("threads"),cfg.getInt("hash")),(0 until cs.length()).map {
            val c=cs.getJSONObject(it); Candidate(c.getInt("i"),Score(c.getString("score")),if(c.has("w")) c.getDouble("w") else null,if(c.has("d")) c.getDouble("d") else null,c.getInt("depth"),c.getLong("nodes"),c.getLong("nps"),c.getLong("ms"),parse(c.getJSONArray("pv")))
        },Move.parse(o.optString("best"),22),parse(o.getJSONArray("forbidden")).toSet(),cached=true)
    }
}
class AnalysisRepository(private val engine:GomokuEngine,private val dao:StoreDao) {
    suspend fun analyze(
        p:Position,
        c:AnalysisConfig,
        e:EngineSpec,
        force:Boolean=false,
        cacheResult:Boolean=true,
        onUpdate:(Analysis)->Unit={}
    ):Analysis {
        val key=sha256("rapfi-3c94c2a|${p.key()}|$e|$c")
        if(!force) dao.cache(key)?.let { return AnalysisCodec.decode(it.json).copy(engine=e) }
        val a=engine.analyze(p,c,e,onUpdate)
        if(cacheResult && a.completed && a.candidates.isNotEmpty()) {
            dao.putCache(CacheEntity(key,AnalysisCodec.encode(a)))
        }
        return a
    }
    fun stop()=engine.stop()
}
