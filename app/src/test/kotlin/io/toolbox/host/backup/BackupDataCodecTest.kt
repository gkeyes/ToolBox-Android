package io.toolbox.host.backup

import io.toolbox.core.data.*
import io.toolbox.tool.packagekit.backup.BackupJson as J
import org.junit.Assert.*
import org.junit.Test

class BackupDataCodecTest {
    @Test fun allSettingsRoundTripIncludingSystemColorsAndTransparency() {
        val original = HostSettings(ThemeMode.MONET_DARK, false, ThemeStyle.MIUIX, true)
        val json = J.obj(J.parse(J.encode(BackupDataCodec.settings(original)).toByteArray()))
        val warnings = mutableListOf<String>()
        assertEquals(original, BackupDataCodec.settings(HostSettings(), json, warnings)); assertTrue(warnings.isEmpty())
    }
    @Test fun unknownFieldsAndEnumsPreserveCurrentValuesAndReport() {
        val warnings = mutableListOf<String>()
        val original = HostSettings(theme = ThemeMode.DARK)
        val data = BackupDataCodec.settings(HostSettings()).toMutableMap().apply { put("theme", "FUTURE"); put("future", true) }
        assertEquals(ThemeMode.DARK, BackupDataCodec.settings(original, data, warnings).theme)
        assertEquals(2, warnings.size)
    }
    @Test fun incompatibleSettingsPreserveAllCurrentValues() {
        val warnings = mutableListOf<String>()
        val original = HostSettings(theme = ThemeMode.LIGHT, backgroundEnabled = false)
        assertEquals(original, BackupDataCodec.settings(original, mapOf("dataVersion" to 2), warnings))
        assertFalse(warnings.isEmpty())
    }
    @Test fun controlDescriptorsAndSecureChunksAreRecognizedWithoutDroppingNormalConfiguration() {
        assertTrue(BackupDataCodec.hostControl("__toolbox.sessions"))
        assertTrue(BackupDataCodec.secureKey("${BackupDataCodec.SECURE}.chunk.1"))
        assertFalse(BackupDataCodec.hostControl("toolbox.runtime.v2.standard.key.0061"))
    }
}
