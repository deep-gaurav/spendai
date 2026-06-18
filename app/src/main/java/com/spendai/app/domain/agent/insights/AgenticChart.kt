package com.spendai.app.domain.agent.insights

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The graph schema the agent can emit. The orchestrator hands
 * the model a fixed vocabulary in the system prompt and refuses
 * any chart type not in this list. Each variant maps 1:1 to an
 * existing Compose chart under
 * [com.spendai.app.ui.insights.charts] so the rendering path is
 * the same one the auto Insights screen uses.
 *
 * Naming: snake_case discriminator values mirror the wording the
 * model sees in the system prompt ("a `donut` chart", "a
 * `bar_vertical` chart", ...). Stable strings — the model
 * contract depends on them.
 *
 * Units: `value` / `values` / `point.value` are in MAJOR units
 * (rupees, not paise). The model reasons in rupees; the renderer
 * formats with [java.text.NumberFormat]. This keeps the
 * "12,450" mental model intact end-to-end and avoids a unit
 * mismatch where the donut shows "12,450" but the kpi says
 * "12,450.00 INR".
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
sealed class AgenticChart {

    abstract val title: String
    abstract val currency: String

    /**
     * A donut chart with a centre total and a side legend.
     * `slices` must sum to `totalLabel`; the renderer does not
     * re-derive the total so the model can round to a tidy
     * display value. `emoji` is optional per slice.
     */
    @Serializable
    @SerialName("donut")
    data class Donut(
        override val title: String,
        override val currency: String,
        val totalLabel: String,
        val slices: List<Slice>,
    ) : AgenticChart() {
        @Serializable
        data class Slice(
            val label: String,
            val value: Double,
            val emoji: String? = null,
        )
    }

    /**
     * A vertical bar chart (one bar per category, x-axis is the
     * label, y-axis is the magnitude). Used for "top N"
     * rankings.
     */
    @Serializable
    @SerialName("bar_vertical")
    data class BarVertical(
        override val title: String,
        override val currency: String,
        val entries: List<Entry>,
    ) : AgenticChart() {
        @Serializable
        data class Entry(
            val label: String,
            val value: Double,
            val trailingLabel: String? = null,
            val emoji: String? = null,
        )
    }

    /**
     * A horizontal bar chart. Same shape as [BarVertical] but
     * rotated 90 degrees — the renderer does not need to know
     * the orientation in advance, the UI picks it.
     */
    @Serializable
    @SerialName("bar_horizontal")
    data class BarHorizontal(
        override val title: String,
        override val currency: String,
        val entries: List<Entry>,
    ) : AgenticChart() {
        @Serializable
        data class Entry(
            val label: String,
            val value: Double,
            val trailingLabel: String? = null,
            val emoji: String? = null,
        )
    }

    /**
     * A line chart with one point per x-tick. `points` may
     * include zero-valued entries for missing days; the
     * renderer densifies the series just like the auto
     * insights line chart. `xLabel` and `yLabel` are optional
     * axis hints.
     */
    @Serializable
    @SerialName("line")
    data class Line(
        override val title: String,
        override val currency: String,
        val points: List<Point>,
        val xLabel: String? = null,
        val yLabel: String? = null,
    ) : AgenticChart() {
        @Serializable
        data class Point(
            val x: String,
            val y: Double,
        )
    }
}
