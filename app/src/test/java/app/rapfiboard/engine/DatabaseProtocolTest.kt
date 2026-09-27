package app.rapfiboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DatabaseProtocolTest {
    private fun encoded(label:String):Int = label.fold(0) { acc,c -> (acc shl 8) or c.code }

    @Test fun decodesRapfiDisplayLabels() {
        assertEquals("73%",decodeDatabaseLabel(encoded("73%")))
        assertEquals("w12",decodeDatabaseLabel(encoded("w12")))
        assertEquals("",decodeDatabaseLabel(-1))
    }

    @Test fun normalizesChildLabelsToParentWinRate() {
        assertEquals(0.73,databaseWinRate("73%")!!,1e-9)
        assertEquals(1.0,databaseWinRate("l*")!!,1e-9)
        assertEquals(0.0,databaseWinRate("w7")!!,1e-9)
        assertEquals(0.5,databaseWinRate("d")!!,1e-9)
        assertNull(databaseWinRate("!"))
    }
}
