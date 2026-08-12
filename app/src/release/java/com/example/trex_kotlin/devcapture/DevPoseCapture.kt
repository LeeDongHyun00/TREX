package com.example.trex_kotlin.devcapture

import android.content.Context
import com.example.trex_kotlin.pose.PoseFrame

/**
 * Developer-only landmark recorder. The release twin: every entry point is a no-op and this file
 * contains no storage, no file handle and no buffer.
 *
 * The debug source set holds the real implementation. Because variant source sets replace rather
 * than merge, a release build cannot link it even by mistake, which is what lets the form-check
 * policy keep promising that the shipped track stores nothing. A governance test reads this file
 * as text and fails if persistence ever appears in it, so the guarantee does not rest on review
 * alone.
 *
 * The signatures must stay identical to the debug object; `compileReleaseKotlin` is what proves
 * they have.
 */
internal object DevPoseCapture {

    const val ENABLED: Boolean = false

    val isEnabled: Boolean get() = false

    fun begin(context: Context, exerciseId: String) = Unit

    fun record(
        timestampMs: Long,
        hasPrimaryPersonLock: Boolean,
        lateralViewQualified: Boolean,
        frame: PoseFrame,
    ) = Unit

    fun end() = Unit
}
