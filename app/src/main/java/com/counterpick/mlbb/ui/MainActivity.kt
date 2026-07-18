package com.counterpick.mlbb.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.counterpick.mlbb.capture.ScreenCaptureService
import com.counterpick.mlbb.data.DataFreshness
import com.counterpick.mlbb.network.OpenMlbbClient
import com.counterpick.mlbb.data.Hero
import com.counterpick.mlbb.data.HeroRole
import com.counterpick.mlbb.data.RankTier
import com.counterpick.mlbb.data.StatsRepository
import com.counterpick.mlbb.engine.DraftEventBus
import com.counterpick.mlbb.engine.DraftState
import com.counterpick.mlbb.engine.HeroRecommendation
import com.counterpick.mlbb.engine.RecommendationEngine
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

/**
 * Two tabs on one screen, sized for a phone:
 *  - Manual Draft: tap heroes into ally/enemy/ban slots yourself and get ranked
 *    recommendations instantly. No OpenCV, no icon templates, no calibration —
 *    works the moment the app installs. This is the recommended way to use the app.
 *  - Live Overlay: the original screen-capture + auto-detect flow, for anyone who
 *    has done the OpenCV/icon-template setup from the README.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREFS = "counterpick_prefs"
        const val KEY_FAVORITES = "favorite_hero_ids"
        val ROLE_COLORS = mapOf(
            HeroRole.TANK to "#3B82F6",
            HeroRole.FIGHTER to "#EF4444",
            HeroRole.ASSASSIN to "#A855F7",
            HeroRole.MAGE to "#06B6D4",
            HeroRole.MARKSMAN to "#F59E0B",
            HeroRole.SUPPORT to "#22C55E"
        )
    }

    private lateinit var prefs: SharedPreferences

    // ---- shared look ----
    private val bgColor = Color.parseColor("#101418")
    private val cardColor = Color.parseColor("#181D24")
    private val mutedText = Color.parseColor("#9AA4B2")
    private val accent = Color.parseColor("#4C8DFF")

    // ---- data ----
    private var statsRepository: StatsRepository? = null
    private var recommendationEngine: RecommendationEngine? = null
    private val favoriteHeroIds = mutableSetOf<Int>()

    // ---- manual draft state ----
    private var manualRank: RankTier = RankTier.MYTHIC
    private val allyPicks = MutableList(5) { -1 }
    private val enemyPicks = MutableList(5) { -1 }
    private val bannedHeroIds = mutableSetOf<Int>()
    private val roleFilter = mutableSetOf<HeroRole>()
    private var showFavoritesOnly = false
    private var searchQuery = ""

    // ---- manual draft views ----
    private lateinit var heroChipGroup: ChipGroup
    private lateinit var allyRow: LinearLayout
    private lateinit var enemyRow: LinearLayout
    private lateinit var banRow: LinearLayout
    private lateinit var recommendationList: LinearLayout
    private lateinit var heroLoadingText: TextView

    // ---- live overlay state/views ----
    private var overlayRank: RankTier = RankTier.MYTHIC
    private lateinit var overlayStatusText: TextView
    private lateinit var overlayToggleButton: MaterialButton

    // ---- tab containers ----
    private lateinit var manualSection: View
    private lateinit var overlaySection: View

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = ScreenCaptureService.buildStartIntent(this, result.resultCode, result.data!!, overlayRank)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadFavorites()
        setContentView(buildRoot())
        observeCaptureState()
        requestNotificationPermissionIfNeeded()
        loadStatsAndPopulate()
    }

    // ============================================================ layout ==

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildRoot(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "MLBB Counter Pick"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Build the draft yourself for instant picks, or auto-detect it live."
            setTextColor(mutedText)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(subtitle)

        val toggleGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val manualTabBtn = tabButton("Manual Draft")
        val overlayTabBtn = tabButton("Live Overlay")
        toggleGroup.addView(manualTabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggleGroup.addView(overlayTabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        manualTabBtn.isChecked = true
        root.addView(toggleGroup)

        manualSection = buildManualDraftSection()
        overlaySection = buildLiveOverlaySection()
        overlaySection.visibility = View.GONE
        root.addView(manualSection)
        root.addView(overlaySection)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == manualTabBtn.id) {
                manualSection.visibility = View.VISIBLE
                overlaySection.visibility = View.GONE
            } else {
                manualSection.visibility = View.GONE
                overlaySection.visibility = View.VISIBLE
            }
        }

        return scroll
    }

    private fun tabButton(label: String): MaterialButton = MaterialButton(this).apply {
        id = View.generateViewId()
        text = label
        textSize = 13f
        isCheckable = true
        cornerRadius = dp(10)
    }

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(cardColor)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14)
        layoutParams = lp
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    // ---------------------------------------------------- Manual draft UI --

    private fun buildManualDraftSection(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Rank picker
        val rankCard = sectionCard()
        rankCard.addView(sectionHeader("Your rank"))
        val rankChips = ChipGroup(this).apply { isSingleSelection = true }
        RankTier.entries.forEach { rank ->
            val chip = Chip(this).apply {
                text = rank.label
                isCheckable = true
                isChecked = rank == manualRank
                setOnClickListener {
                    manualRank = rank
                    refreshRecommendations()
                }
            }
            rankChips.addView(chip)
        }
        rankCard.addView(rankChips)
        container.addView(rankCard)

        // Draft board
        val boardCard = sectionCard()
        boardCard.addView(sectionHeader("Draft board"))
        boardCard.addView(smallLabel("Your team"))
        allyRow = pickRow()
        boardCard.addView(allyRow)
        boardCard.addView(smallLabel("Enemy team"))
        enemyRow = pickRow()
        boardCard.addView(enemyRow)
        boardCard.addView(smallLabel("Banned"))
        banRow = pickRow()
        boardCard.addView(banRow)

        val clearBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear draft"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            setOnClickListener {
                for (i in 0 until 5) { allyPicks[i] = -1; enemyPicks[i] = -1 }
                bannedHeroIds.clear()
                refreshRecommendations()
            }
        }
        boardCard.addView(clearBtn)
        container.addView(boardCard)

        // Hero picker
        val pickerCard = sectionCard()
        pickerCard.addView(sectionHeader("Tap a hero to assign it"))

        val search = EditText(this).apply {
            hint = "Search heroes…"
            setHintTextColor(mutedText)
            setTextColor(Color.WHITE)
            textSize = 14f
            addTextChangedListener { text ->
                searchQuery = text?.toString().orEmpty()
                populateHeroChips()
            }
        }
        pickerCard.addView(search)

        val filterChips = ChipGroup(this).apply {
            isSingleSelection = false
            setPadding(0, dp(8), 0, dp(4))
        }
        HeroRole.entries.forEach { role ->
            val chip = Chip(this).apply {
                text = role.name.lowercase().replaceFirstChar { it.uppercase() }
                isCheckable = true
                setOnCheckedChangeListener { _, checked ->
                    if (checked) roleFilter.add(role) else roleFilter.remove(role)
                    populateHeroChips()
                }
            }
            filterChips.addView(chip)
        }
        val favChip = Chip(this).apply {
            text = "★ Favorites"
            isCheckable = true
            setOnCheckedChangeListener { _, checked ->
                showFavoritesOnly = checked
                populateHeroChips()
            }
        }
        filterChips.addView(favChip)
        pickerCard.addView(filterChips)

        heroLoadingText = TextView(this).apply {
            text = "Loading hero database…"
            setTextColor(mutedText)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }
        pickerCard.addView(heroLoadingText)

        heroChipGroup = ChipGroup(this).apply {
            isSingleSelection = false
            setPadding(0, dp(4), 0, 0)
        }
        pickerCard.addView(heroChipGroup)
        container.addView(pickerCard)

        // Recommendations
        val recCard = sectionCard()
        recCard.addView(sectionHeader("Best next pick by role"))
        recommendationList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recCard.addView(recommendationList)
        container.addView(recCard)

        return container
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(mutedText)
        textSize = 11f
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun pickRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun EditText.addTextChangedListener(onChange: (Editable?) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange(s)
        })
    }

    // ---------------------------------------------------- Live overlay UI --

    private fun buildLiveOverlaySection(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val rankCard = sectionCard()
        rankCard.addView(sectionHeader("Your rank"))
        val rankChips = ChipGroup(this).apply { isSingleSelection = true }
        RankTier.entries.forEach { rank ->
            val chip = Chip(this).apply {
                text = rank.label
                isCheckable = true
                isChecked = rank == overlayRank
                setOnClickListener { overlayRank = rank }
            }
            rankChips.addView(chip)
        }
        rankCard.addView(rankChips)
        container.addView(rankCard)

        val overlayCard = sectionCard()
        overlayCard.addView(sectionHeader("Auto-detect overlay"))
        overlayStatusText = TextView(this).apply {
            text = "Overlay: stopped"
            setTextColor(mutedText)
            setPadding(0, 0, 0, dp(8))
        }
        overlayCard.addView(overlayStatusText)

        overlayToggleButton = MaterialButton(this).apply {
            text = "Start overlay"
            setOnClickListener { onToggleClicked() }
        }
        overlayCard.addView(overlayToggleButton)

        val calibrationNote = TextView(this).apply {
            text = "Requires one-time setup: grant \"draw over other apps\", add OpenCV per the README, " +
                "drop hero icon crops into assets/hero_icons/<name>.png, and adjust draft_layout.json if " +
                "slot detection misses. Until then, use Manual Draft — it needs none of that."
            setTextColor(mutedText)
            textSize = 11f
            gravity = Gravity.START
            setPadding(0, dp(14), 0, 0)
        }
        overlayCard.addView(calibrationNote)
        container.addView(overlayCard)

        return container
    }

    private fun onToggleClicked() {
        if (DraftEventBus.isCapturing.value) {
            stopService(Intent(this, ScreenCaptureService::class.java))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant \"draw over other apps\" first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun observeCaptureState() {
        lifecycleScope.launch {
            DraftEventBus.isCapturing.collect { active ->
                overlayStatusText.text = if (active) "Overlay: running — switch to MLBB" else "Overlay: stopped"
                overlayToggleButton.text = if (active) "Stop overlay" else "Start overlay"
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    // ============================================================ data ====

    private fun loadFavorites() {
        favoriteHeroIds.clear()
        prefs.getString(KEY_FAVORITES, "")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.let {
            favoriteHeroIds.addAll(it)
        }
    }

    private fun saveFavorites() {
        prefs.edit().putString(KEY_FAVORITES, favoriteHeroIds.joinToString(",")).apply()
    }

    private fun loadStatsAndPopulate() {
        lifecycleScope.launch {
            val repo = StatsRepository.get(this@MainActivity)
            statsRepository = repo
            recommendationEngine = RecommendationEngine(repo)
            heroLoadingText.text = "${repo.heroesById.size} heroes loaded — tap one to assign it"
            populateHeroChips()
            refreshRecommendations()
        }
    }

    // ============================================================ hero grid

    private fun populateHeroChips() {
        val repo = statsRepository ?: return
        heroChipGroup.removeAllViews()

        val heroes = repo.heroesById.values
            .filter { hero ->
                (roleFilter.isEmpty() || hero.roles.any { it in roleFilter }) &&
                    (!showFavoritesOnly || hero.id in favoriteHeroIds) &&
                    (searchQuery.isBlank() || hero.name.contains(searchQuery, ignoreCase = true))
            }
            .sortedWith(compareByDescending<Hero> { it.id in favoriteHeroIds }.thenBy { it.name })

        for (hero in heroes) {
            val unavailable = hero.id in bannedHeroIds || hero.id in allyPicks || hero.id in enemyPicks
            val chip = Chip(this).apply {
                text = (if (hero.id in favoriteHeroIds) "★ " else "") + hero.name
                isCheckable = false
                alpha = if (unavailable) 0.4f else 1f
                val roleColor = Color.parseColor(ROLE_COLORS[hero.roles.first()] ?: "#4C8DFF")
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(roleColor).withAlpha(60)
                setTextColor(Color.WHITE)
                setOnClickListener { showHeroActionDialog(hero) }
            }
            heroChipGroup.addView(chip)
        }
    }

    private fun android.content.res.ColorStateList.withAlpha(alpha: Int): android.content.res.ColorStateList {
        val c = this.defaultColor
        return android.content.res.ColorStateList.valueOf(Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c)))
    }

    private fun showHeroActionDialog(hero: Hero) {
        val isFavorite = hero.id in favoriteHeroIds
        val options = arrayOf(
            "Add to your team",
            "Add to enemy team",
            "Ban",
            "Remove from draft",
            if (isFavorite) "★ Remove favorite" else "★ Add favorite"
        )
        AlertDialog.Builder(this)
            .setTitle(hero.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> assignToSlot(allyPicks, hero.id, "your team")
                    1 -> assignToSlot(enemyPicks, hero.id, "the enemy team")
                    2 -> { bannedHeroIds.add(hero.id); afterDraftChange() }
                    3 -> {
                        allyPicks.replaceAll { if (it == hero.id) -1 else it }
                        enemyPicks.replaceAll { if (it == hero.id) -1 else it }
                        bannedHeroIds.remove(hero.id)
                        afterDraftChange()
                    }
                    4 -> {
                        if (isFavorite) favoriteHeroIds.remove(hero.id) else favoriteHeroIds.add(hero.id)
                        saveFavorites()
                        populateHeroChips()
                    }
                }
            }
            .show()
    }

    private fun assignToSlot(slots: MutableList<Int>, heroId: Int, teamLabel: String) {
        val emptyIndex = slots.indexOf(-1)
        if (emptyIndex == -1) {
            Toast.makeText(this, "All 5 slots on $teamLabel are already filled", Toast.LENGTH_SHORT).show()
            return
        }
        slots[emptyIndex] = heroId
        afterDraftChange()
    }

    private fun afterDraftChange() {
        populateHeroChips()
        refreshRecommendations()
    }

    // ==================================================== draft board UI ==

    private fun refreshRecommendations() {
        val repo = statsRepository ?: return
        val engine = recommendationEngine ?: return

        renderPickRow(allyRow, allyPicks, repo)
        renderPickRow(enemyRow, enemyPicks, repo)
        renderBanRow(banRow, repo)

        val draft = DraftState(
            rank = manualRank,
            allyPicks = allyPicks.toList(),
            enemyPicks = enemyPicks.toList(),
            bannedHeroIds = bannedHeroIds.toSet()
        )

        // Score immediately from whatever's cached (live or offline fallback) so the UI
        // never blocks on the network, then kick off a live refresh in the background and
        // re-render if it changed anything. This is what makes "every calculation" live
        // without every tap feeling laggy on a slow connection.
        renderRecommendationsByRole(engine.recommendByRole(draft, perRole = 3), repo.freshness)
        lifecycleScope.launch {
            repo.refreshLive(manualRank, allyPicks, enemyPicks)
            renderRecommendationsByRole(engine.recommendByRole(draft, perRole = 3), repo.freshness)
        }
    }

    private fun renderPickRow(row: LinearLayout, picks: List<Int>, repo: StatsRepository) {
        row.removeAllViews()
        for (heroId in picks) {
            row.addView(slotChip(if (heroId == -1) null else repo.heroesById[heroId]))
        }
    }

    private fun renderBanRow(row: LinearLayout, repo: StatsRepository) {
        row.removeAllViews()
        if (bannedHeroIds.isEmpty()) {
            row.addView(slotChip(null))
            return
        }
        for (heroId in bannedHeroIds) {
            row.addView(slotChip(repo.heroesById[heroId], isBan = true))
        }
    }

    private fun slotChip(hero: Hero?, isBan: Boolean = false): View {
        val box = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(dp(72), dp(36))
            lp.marginEnd = dp(6)
            lp.topMargin = dp(2)
            layoutParams = lp
            setBackgroundColor(if (hero == null) Color.parseColor("#20242C") else Color.parseColor(if (isBan) "#3A1F24" else "#1F2A3A"))
        }
        val label = TextView(this).apply {
            text = hero?.name ?: "Empty"
            setTextColor(if (hero == null) mutedText else Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 2
        }
        box.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (hero != null) {
            box.setOnClickListener { showHeroActionDialog(hero) }
        }
        return box
    }

    private fun roleLabel(role: HeroRole): String = role.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun relativeTime(epochMs: Long): String {
        val diffSec = (System.currentTimeMillis() - epochMs) / 1000
        return when {
            diffSec < 60 -> "just now"
            diffSec < 3600 -> "${diffSec / 60}m ago"
            diffSec < 86400 -> "${diffSec / 3600}h ago"
            else -> "${diffSec / 86400}d ago"
        }
    }

    private fun renderRecommendationsByRole(byRole: Map<HeroRole, List<HeroRecommendation>>, freshness: DataFreshness) {
        recommendationList.removeAllViews()

        val lastLiveAt = statsRepository?.lastLiveFetchAt ?: 0L
        val freshnessLabel = TextView(this).apply {
            text = when {
                freshness == DataFreshness.LIVE -> "● Live stats"
                lastLiveAt > 0L -> "○ Offline fallback — last live ${relativeTime(lastLiveAt)} · tap to retry"
                else -> "○ Offline fallback stats — tap to retry"
            }
            setTextColor(if (freshness == DataFreshness.LIVE) Color.parseColor("#4ADE80") else mutedText)
            textSize = 10f
            setPadding(0, 0, 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                text = "Checking connection…"
                lifecycleScope.launch {
                    statsRepository?.refreshLive(manualRank, allyPicks, enemyPicks)
                    if (statsRepository?.freshness != DataFreshness.LIVE) {
                        Toast.makeText(
                            this@MainActivity,
                            OpenMlbbClient.lastDiagnostic,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // Re-render fully so the recommendation numbers pick up anything that
                    // just came back live, not just this label.
                    refreshRecommendations()
                }
            }
        }
        recommendationList.addView(freshnessLabel)

        val anyPicks = byRole.values.any { it.isNotEmpty() }
        if (!anyPicks) {
            recommendationList.addView(TextView(this).apply {
                text = "No heroes available — clear a slot or ban."
                setTextColor(mutedText)
                textSize = 12f
            })
            return
        }

        // Fixed, game-familiar order rather than HeroRole enum declaration order.
        val roleOrder = listOf(HeroRole.TANK, HeroRole.FIGHTER, HeroRole.ASSASSIN, HeroRole.MAGE, HeroRole.MARKSMAN, HeroRole.SUPPORT)
        for (role in roleOrder) {
            val recs = byRole[role].orEmpty()
            if (recs.isEmpty()) continue

            recommendationList.addView(TextView(this).apply {
                text = roleLabel(role)
                setTextColor(Color.parseColor(ROLE_COLORS[role] ?: "#4C8DFF"))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(10)
                layoutParams = lp
            })

            recs.forEachIndexed { index, rec ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(if (index == 0) Color.parseColor("#1B2A20") else Color.parseColor("#171B21"))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = dp(4)
                    layoutParams = lp
                    setOnClickListener { showHeroActionDialog(rec.hero) }
                }
                val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                headerRow.addView(TextView(this).apply {
                    text = "${index + 1}. ${rec.hero.name}"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                headerRow.addView(TextView(this).apply {
                    text = "${(rec.estimatedWinChance * 100).toInt()}%"
                    setTextColor(accent)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                })
                row.addView(headerRow)
                val roleLine = rec.hero.roles.joinToString(" / ") { roleLabel(it) }
                val slotNote = if (rec.openEnemySlots > 0) " · ${rec.openEnemySlots} enemy pick(s) still open" else " · enemy team locked"
                row.addView(TextView(this).apply {
                    text = roleLine + slotNote
                    setTextColor(mutedText)
                    textSize = 11f
                })
                if (rec.reasons.isNotEmpty()) {
                    row.addView(TextView(this).apply {
                        text = rec.reasons.joinToString(" · ")
                        setTextColor(mutedText)
                        textSize = 11f
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                recommendationList.addView(row)
            }
        }
    }
}
