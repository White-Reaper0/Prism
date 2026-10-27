package com.nevcortex.prism.evidence

/**
 * Uncertainty / evidence scaffolds. The earlier brief named a Doubt Engine, Belief Ledger, and
 * Scar Tissue Memory. No such code existed in this workspace, so these are explicit interfaces
 * that downstream work can implement. They are intentionally NOT faked: a confidence value is
 * only produced when a real producer (VIO tracking quality, sensor health, perception) feeds it.
 */
interface DoubtEngine {
    /** Aggregate uncertainty from available producers. Returns null if none reported. */
    fun aggregate(): DoubtReport?
}

data class DoubtReport(
    val sources: List<String>,
    val overallConfidence: Float,   // [0..1]; -1 if no source
    val reasons: List<String>
)

interface BeliefLedger {
    fun record(entry: LedgerEntry)
    fun entries(): List<LedgerEntry>
}

data class LedgerEntry(
    val timestampMs: Long,
    val source: String,
    val claim: String,
    val confidence: Float,
    val evidence: String
)

/** Persists belief-ledger entries to disk so they survive across sessions. */
class FileBeliefLedger(private val root: java.io.File) : BeliefLedger {
    private val file = java.io.File(root, "belief_ledger.jsonl").also { root.mkdirs() }
    private val entries = java.util.concurrent.CopyOnWriteArrayList<LedgerEntry>()

    init { reload() }

    override fun record(entry: LedgerEntry) {
        entries.add(entry)
        file.appendText(org.json.JSONObject().apply {
            put("t", entry.timestampMs); put("source", entry.source)
            put("claim", entry.claim); put("conf", entry.confidence); put("evidence", entry.evidence)
        }.toString() + "\n")
    }

    override fun entries(): List<LedgerEntry> = entries.toList()

    private fun reload() {
        if (!file.exists()) return
        runCatching {
            file.readLines().forEach { l ->
                if (l.isNotBlank()) {
                    val j = org.json.JSONObject(l)
                    entries.add(LedgerEntry(
                        j.getLong("t"), j.getString("source"), j.getString("claim"),
                        j.getDouble("conf").toFloat(), j.optString("evidence")
                    ))
                }
            }
        }
    }
}

/** "Scar tissue" memory: records failures/lost-tracking events to avoid repeating mistakes. */
class ScarTissueMemory(private val root: java.io.File) {
    private val file = java.io.File(root, "scar_tissue.jsonl").also { root.mkdirs() }
    fun record(event: String, context: String) {
        file.appendText(org.json.JSONObject().apply {
            put("t", System.currentTimeMillis()); put("event", event); put("context", context)
        }.toString() + "\n")
    }
    fun scars(): List<String> = if (file.exists()) file.readLines() else emptyList()
}
