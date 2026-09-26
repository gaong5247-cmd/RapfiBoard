package app.rapfiboard
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.rapfiboard.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class AppTests {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun navigateReviewAndEngines() { rule.onNodeWithText("Review",useUnmergedTree=true).performClick();rule.onNodeWithText("A better move, next time.").assertExists();rule.onNodeWithText("Engines",useUnmergedTree=true).performClick();rule.onNodeWithText("Engine room").assertExists() }
    @Test fun boardIsAccessible() { rule.onNodeWithContentDescription("15×15 오목판, 0수, 흑 차례").assertExists() }
}

@RunWith(AndroidJUnit4::class)
class EngineIntegrationTests {
    @Test fun realNnueAndClassical() = kotlinx.coroutines.runBlocking {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine=app.rapfiboard.engine.EngineManager(context)
        try {
            val p=app.rapfiboard.core.Position().play(app.rapfiboard.core.Move(7,7)).play(app.rapfiboard.core.Move(8,8))
            for(classical in listOf(false,true)) {
                val a=engine.analyze(p,app.rapfiboard.engine.AnalysisConfig(timeMs=500,nodes=10000,multiPv=3,threads=1),app.rapfiboard.engine.EngineSpec(classical=classical))
                org.junit.Assert.assertNotNull(a.best)
                org.junit.Assert.assertTrue(a.candidates.isNotEmpty())
                engine.close()
            }
        } finally { engine.close() }
    }
    @Test fun databaseRoundTripAndDuplicates() = kotlinx.coroutines.runBlocking {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,app.rapfiboard.data.AppDatabase::class.java).build()
        try {
            val repo=app.rapfiboard.data.DatabaseRepository(db)
            val p=app.rapfiboard.core.Position().play(app.rapfiboard.core.Move(7,7))
            org.junit.Assert.assertTrue(repo.save(p,"Game",1))
            org.junit.Assert.assertFalse(repo.save(p,"Game",1))
            org.junit.Assert.assertEquals(1,repo.dao.stats(app.rapfiboard.core.Position().key()).single().wins)
            org.junit.Assert.assertEquals(0,repo.import(repo.export()))
        } finally { db.close() }
    }
}
