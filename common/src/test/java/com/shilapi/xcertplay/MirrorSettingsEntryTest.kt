package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.aa.AaPreferences
import com.shilapi.xcertplay.adb.AdbPreferences
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Android mode pair is only reachable through the settings cards, so the entries are asserted
 * where the driver looks for them: the receiver on the Connection card next to "Phone type", the
 * test sender under Diagnostics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w600dp-h700dp")
class MirrorSettingsEntryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
    }

    @Test fun receiverIsOfferedOnTheConnectionCardWithItsAddress() {
        AdbPreferences.savePhoneHost(context, "192.168.1.40")
        AdbPreferences.savePhonePort(context, 5555)
        val screen = openCategory(R.string.connection)

        val labels = texts(screen).map { it.text.toString() }
        assertTrue(labels.toString(), labels.contains(screen.getString(R.string.aa_receiver_button)))
        assertTrue(
            labels.toString(),
            labels.contains(screen.getString(R.string.aa_receiver_address_button, "192.168.1.40:5555")),
        )
    }

    /** With no address typed, the entry points at this head unit's own adbd — the self-test loop. */
    @Test fun receiverDefaultsToTheHeadUnitsOwnAdbd() {
        val screen = openCategory(R.string.connection)
        val expected = "127.0.0.1:${AdbPreferences.DEFAULT_PORT}"
        assertTrue(
            texts(screen).map { it.text.toString() }
                .contains(screen.getString(R.string.aa_receiver_address_button, expected)),
        )
    }

    @Test fun theTestSenderSitsUnderDiagnostics() {
        val screen = openCategory(R.string.diagnostics)
        val labels = texts(screen).map { it.text.toString() }
        assertTrue(labels.toString(), labels.contains(screen.getString(R.string.aa_sender_button)))
    }

    @Test fun thePhoneTypeHintNoLongerClaimsTheReceiverIsMissing() {
        val screen = openCategory(R.string.connection)
        val hint = screen.getString(R.string.settings_phone_type_hint)
        assertTrue(hint, !hint.contains("not part of this build"))
    }

    @Test fun bothEndsAgreeOnTheDefaultPort() {
        assertEquals(8790, AaPreferences.receiverPort(context))
        assertEquals(8790, AaPreferences.senderPort(context))
    }

    private fun openCategory(category: Int): DiPlayActivity {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get().also { activity = it }
        descendants(screen.window.decorView)
            .single {
                it.contentDescription == screen.getString(
                    R.string.settings_open_category,
                    screen.getString(category),
                )
            }
            .performClick()
        return screen
    }

    private fun texts(screen: DiPlayActivity) =
        descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
