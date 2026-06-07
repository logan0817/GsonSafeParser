package io.github.logan.gsonsafeparser.demo.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import io.github.logan.gsonsafeparser.demo.R
import io.github.logan.gsonsafeparser.demo.databinding.DialogOutputDetailBinding
import io.github.logan.gsonsafeparser.demo.databinding.IncludeOutputPreviewPanelBinding
import io.github.logan.gsonsafeparser.demo.databinding.IncludeResultPanelBinding
import io.github.logan.gsonsafeparser.demo.support.buildOutputBlockPreview
import io.github.logan.gsonsafeparser.demo.support.cleanDisplayText
import io.github.logan.gsonsafeparser.demo.support.sanitizeClipboardReportText

internal data class DemoOutputPanelState(
    val statusText: String,
    val statusColor: Int,
    val outputPreview: String,
    val showDefaultValueNote: Boolean,
    val actual: String,
    val expected: String,
    val events: String,
    val contractReport: String,
    val observerReport: String,
    val diagnostics: String,
    val error: String
)

internal class DemoOutputPanelController(
    private val activity: Activity,
    private val outputPreviewBinding: IncludeOutputPreviewPanelBinding,
    private val resultBinding: IncludeResultPanelBinding
) {
    private var outputPreviewDetailText: String = ""
    private var actualDetailText: String = ""
    private var expectedDetailText: String = ""
    private var eventsDetailText: String = ""
    private var contractReportDetailText: String = ""
    private var observerReportDetailText: String = ""
    private var diagnosticsDetailText: String = ""
    private var errorDetailText: String = ""

    fun initialize(defaultValueNoteText: String) {
        outputPreviewBinding.outputPreviewNoteView.text = defaultValueNoteText
        bindOutputPreview(
            outputPreviewBinding.outputPreviewView,
            outputPreviewBinding.outputPreviewHintButton,
            R.string.demo_output_preview_title
        ) {
            outputPreviewDetailText
        }
        bindOutputPreview(resultBinding.actualView, resultBinding.actualHintButton, R.string.demo_actual_title) {
            actualDetailText
        }
        bindOutputPreview(resultBinding.expectedView, resultBinding.expectedHintButton, R.string.demo_expected_title) {
            expectedDetailText
        }
        bindOutputPreview(resultBinding.eventsView, resultBinding.eventsHintButton, R.string.demo_events_title) {
            eventsDetailText
        }
        bindOutputPreview(
            resultBinding.contractReportView,
            resultBinding.contractReportHintButton,
            R.string.demo_contract_report_title
        ) {
            contractReportDetailText
        }
        bindOutputPreview(
            resultBinding.observerReportView,
            resultBinding.observerReportHintButton,
            R.string.demo_observer_report_title
        ) {
            observerReportDetailText
        }
        bindOutputPreview(
            resultBinding.diagnosticsView,
            resultBinding.diagnosticsHintButton,
            R.string.demo_diagnostics_title
        ) {
            diagnosticsDetailText
        }
        bindOutputPreview(resultBinding.errorView, resultBinding.errorHintButton, R.string.demo_error_title) {
            errorDetailText
        }
    }

    fun render(state: DemoOutputPanelState) {
        resultBinding.statusView.text = state.statusText
        resultBinding.statusView.setTextColor(state.statusColor)
        outputPreviewDetailText = cleanDisplayText(state.outputPreview)
        actualDetailText = cleanDisplayText(state.actual)
        expectedDetailText = cleanDisplayText(state.expected)
        eventsDetailText = cleanDisplayText(state.events)
        contractReportDetailText = cleanDisplayText(state.contractReport)
        observerReportDetailText = cleanDisplayText(state.observerReport)
        diagnosticsDetailText = cleanDisplayText(state.diagnostics)
        errorDetailText = cleanDisplayText(state.error)

        outputPreviewBinding.outputPreviewView.text = buildOutputBlockPreview(outputPreviewDetailText)
        outputPreviewBinding.outputPreviewNoteView.visibility = if (state.showDefaultValueNote) View.VISIBLE else View.GONE
        resultBinding.actualView.text = buildOutputBlockPreview(actualDetailText)
        resultBinding.expectedView.text = buildOutputBlockPreview(expectedDetailText)
        resultBinding.eventsView.text = buildOutputBlockPreview(eventsDetailText)
        resultBinding.contractReportView.text = buildOutputBlockPreview(contractReportDetailText)
        resultBinding.observerReportView.text = buildOutputBlockPreview(observerReportDetailText)
        resultBinding.diagnosticsView.text = buildOutputBlockPreview(diagnosticsDetailText)
        resultBinding.errorView.text = buildOutputBlockPreview(errorDetailText)
        refreshOutputHintVisibility()
    }

    fun refreshOutputHintVisibility() {
        updateOutputHintVisibility(outputPreviewBinding.outputPreviewView, outputPreviewBinding.outputPreviewHintButton) {
            outputPreviewDetailText
        }
        updateOutputHintVisibility(resultBinding.actualView, resultBinding.actualHintButton) { actualDetailText }
        updateOutputHintVisibility(resultBinding.expectedView, resultBinding.expectedHintButton) { expectedDetailText }
        updateOutputHintVisibility(resultBinding.eventsView, resultBinding.eventsHintButton) { eventsDetailText }
        updateOutputHintVisibility(resultBinding.contractReportView, resultBinding.contractReportHintButton) {
            contractReportDetailText
        }
        updateOutputHintVisibility(resultBinding.observerReportView, resultBinding.observerReportHintButton) {
            observerReportDetailText
        }
        updateOutputHintVisibility(resultBinding.diagnosticsView, resultBinding.diagnosticsHintButton) {
            diagnosticsDetailText
        }
        updateOutputHintVisibility(resultBinding.errorView, resultBinding.errorHintButton) { errorDetailText }
    }

    private fun bindOutputPreview(
        previewView: TextView,
        hintButton: ImageButton,
        titleResId: Int,
        contentProvider: () -> String = { previewView.text.toString() }
    ) {
        previewView.openDetailOnClick(titleResId, contentProvider)
        hintButton.openDetailOnClick(titleResId, contentProvider)
        hintButton.visibility = View.GONE
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateOutputHintVisibility(previewView, hintButton, contentProvider)
        }
        previewView.post {
            updateOutputHintVisibility(previewView, hintButton, contentProvider)
        }
    }

    private fun updateOutputHintVisibility(
        previewView: TextView,
        hintButton: ImageButton,
        contentProvider: () -> String = { previewView.text.toString() }
    ) {
        val hasHiddenDetail = contentProvider().trimStart() != previewView.text.toString().trimStart()
        hintButton.visibility = if (hasHiddenDetail || previewView.isPreviewTextTruncated()) View.VISIBLE else View.GONE
    }

    private fun View.openDetailOnClick(titleResId: Int, contentProvider: () -> String) {
        setOnClickListener {
            showOutputDetailDialog(activity.getString(titleResId), contentProvider())
        }
    }

    private fun showOutputDetailDialog(title: String, content: String) {
        val detailBinding = DialogOutputDetailBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity)
            .setView(detailBinding.root)
            .create()

        detailBinding.detailTitleView.text = title
        detailBinding.detailContentView.text = cleanDisplayText(content)
        detailBinding.copySectionButton.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(title, sanitizeClipboardReportText(detailBinding.detailContentView.text.toString()))
            )
            Toast.makeText(activity, activity.getString(R.string.demo_copy_section_toast), Toast.LENGTH_SHORT).show()
        }
        detailBinding.closeDetailButton.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun TextView.isPreviewTextTruncated(): Boolean {
        val textLayout = layout ?: return false
        val visibleLineCount = if (maxLines > 0) minOf(textLayout.lineCount, maxLines) else textLayout.lineCount
        if (visibleLineCount <= 0) return false
        if (textLayout.lineCount > visibleLineCount) return true
        return textLayout.getEllipsisCount(visibleLineCount - 1) > 0
    }
}
