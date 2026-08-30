package com.qalabox.emu.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qalabox.emu.EmulatorActivity
import com.qalabox.emu.R
import com.qalabox.emu.core.ContainerManager
import com.qalabox.emu.core.LogStore
import com.qalabox.emu.core.ProfileEngine
import com.qalabox.emu.core.RuntimeManager
import com.qalabox.emu.core.SettingsStore
import com.qalabox.emu.core.model.Container
import com.qalabox.emu.core.model.GameProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * شاشة الألعاب — بطاقات البروفايلات المضبوطة مسبقاً.
 * الضغط المطوّل = إعدادات اللعبة (الحاوية + نمط الأداء).
 */
class GamesFragment : Fragment() {

    private lateinit var settings: SettingsStore
    private lateinit var adapter: GamesAdapter
    private var banner: View? = null
    private var gamesEmpty: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_games, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsStore(requireContext())

        val recycler = view.findViewById<RecyclerView>(R.id.games_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val profiles = ProfileEngine.loadProfiles(requireContext())
        adapter = GamesAdapter(profiles)
        recycler.adapter = adapter

        banner = view.findViewById(R.id.runtime_banner)
        banner?.findViewById<TextView>(R.id.banner_btn)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SettingsFragment())
                .commit()
        }
        gamesEmpty = view.findViewById(R.id.games_empty)
        refreshBanner()
    }

    override fun onResume() {
        super.onResume()
        refreshBanner()
    }

    private fun refreshBanner() {
        val v = banner ?: return
        v.visibility = if (RuntimeManager.isInstalled(requireContext())) View.GONE else View.VISIBLE
        gamesEmpty?.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun launchGame(profile: GameProfile) {
        val ctx = requireContext()
        if (!RuntimeManager.isInstalled(ctx)) {
            Toast.makeText(ctx, R.string.runtime_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        val containers = ContainerManager.list(ctx)
        if (containers.isEmpty()) {
            Toast.makeText(ctx, R.string.game_missing_container, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = ctx.getSharedPreferences("profile_prefs", 0)
        val cid = prefs.getString("profile_container_${profile.id}", null)
            ?: settings.selectedContainerId
            ?: containers.first().id
        val container = containers.firstOrNull { it.id == cid } ?: containers.first()

        lifecycleScope.launch(Dispatchers.IO) {
            // حماية من أي استثناء غير متوقع في البحث/التحليل — بدل انهيار التطبيق
            val prepared = runCatching {
                ProfileEngine.prepareSession(ctx, container, profile)
            }.getOrElse { prof ->
                LogStore.append("Games", "خطأ تحضير الجلسة: ${prof.message}")
                null
            }
            withContext(Dispatchers.Main) {
                if (prepared == null || !prepared.ready) {
                    showManualExeDialog(container, profile)
                } else if (prepared.warnings.isNotEmpty()) {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.fixes_applied)
                        .setMessage((prepared.fixes + prepared.warnings).joinToString("\n• ", "• "))
                        .setPositiveButton(R.string.game_play) { _, _ ->
                            startEmulator(container, profile)
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                } else {
                    LogStore.append("Games", "تشغيل ${profile.nameAr} — ${prepared.fixes.size} إصلاح")
                    Toast.makeText(
                        ctx,
                        getString(R.string.game_profile_applied, prepared.fixes.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    startEmulator(container, profile)
                }
            }
        }
    }

    private fun showManualExeDialog(container: Container, profile: GameProfile) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.game_pick_exe_hint)
            setSingleLine()
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.game_pick_exe) + " — ${profile.nameAr}")
            .setMessage(getString(R.string.game_exe_not_found, profile.exeCandidates.joinToString(" / ")))
            .setView(input)
            .setPositiveButton(R.string.game_add_manual) { _, _ ->
                val rel = input.text.toString().trim()
                if (rel.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val prepared = runCatching {
                            ProfileEngine.prepareSession(ctx, container, profile, rel)
                        }.getOrElse { prof ->
                            LogStore.append("Games", "خطأ تحضير الجلسة اليدوية: ${prof.message}")
                            null
                        }
                        withContext(Dispatchers.Main) {
                            if (prepared?.ready == true) startEmulator(container, profile)
                            else Toast.makeText(ctx, R.string.emu_start_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGameSettings(profile: GameProfile) {
        val ctx = requireContext()
        val pm = PopupMenu(ctx, requireView().findViewById(R.id.game_play_btn))
        pm.menu.add(getString(R.string.select_container))
        pm.menu.add(getString(R.string.performance_preset))
        pm.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.select_container) -> showContainerPicker(profile)
                getString(R.string.performance_preset) -> showPresetPicker(profile)
            }
            true
        }
        pm.show()
    }

    private fun showContainerPicker(profile: GameProfile) {
        val ctx = requireContext()
        val containers = ContainerManager.list(ctx)
        if (containers.isEmpty()) {
            Toast.makeText(ctx, R.string.game_missing_container, Toast.LENGTH_LONG).show()
            return
        }
        val names = containers.map { it.name }.toTypedArray()
        val prefs = ctx.getSharedPreferences("profile_prefs", 0)
        val current = prefs.getString("profile_container_${profile.id}", null)
        val checked = containers.indexOfFirst { it.id == current }.coerceAtLeast(0)

        AlertDialog.Builder(ctx)
            .setTitle("${profile.nameAr} — ${getString(R.string.select_container)}")
            .setSingleChoiceItems(names, checked) { dlg, which ->
                prefs.edit().putString("profile_container_${profile.id}", containers[which].id).apply()
                dlg.dismiss()
            }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    /** نمط أداء خاص باللعبة — يتجاوز الإعداد العام (يرسل عبر بيئة الجلسة) */
    private fun showPresetPicker(profile: GameProfile) {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("profile_prefs", 0)
        val current = prefs.getString("profile_preset_${profile.id}", null)
        val presets = listOf("fast", "balanced", "compat")
        val labels = listOf(
            getString(R.string.preset_fast),
            getString(R.string.preset_balanced),
            getString(R.string.preset_compat)
        )
        val checked = presets.indexOfFirst { it == current }.coerceAtLeast(0)

        AlertDialog.Builder(ctx)
            .setTitle("${profile.nameAr} — ${getString(R.string.performance_preset)}")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dlg, which ->
                prefs.edit().putString("profile_preset_${profile.id}", presets[which]).apply()
                dlg.dismiss()
            }
            .setNeutralButton(getString(R.string.game_default_preset)) { _, _ ->
                prefs.edit().remove("profile_preset_${profile.id}").apply()
            }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    private fun startEmulator(container: Container, profile: GameProfile) {
        val i = Intent(requireContext(), EmulatorActivity::class.java)
            .putExtra("container_id", container.id)
            .putExtra("title", profile.nameAr)
        startActivity(i)
    }

    /* ─────────────── المحوّل ─────────────── */
    inner class GamesAdapter(private val items: List<GameProfile>) :
        RecyclerView.Adapter<GamesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.game_icon)
            val name: TextView = v.findViewById(R.id.game_name)
            val subtitle: TextView = v.findViewById(R.id.game_subtitle)
            val play: TextView = v.findViewById(R.id.game_play_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_game, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = items[pos]
            h.icon.text = p.icon.ifBlank { p.nameAr.take(1) }
            h.name.text = p.nameAr
            h.subtitle.text = p.notesAr
            h.itemView.contentDescription = "${p.nameAr} — ${h.itemView.context.getString(R.string.game_settings)}"
            h.play.setOnClickListener { launchGame(p) }
            h.itemView.setOnLongClickListener {
                showGameSettings(p); true
            }
        }
    }
}
