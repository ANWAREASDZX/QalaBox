package com.qalabox.emu.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.qalabox.emu.R
import com.qalabox.emu.core.ContainerManager
import com.qalabox.emu.core.LogStore
import com.qalabox.emu.core.SettingsStore
import com.qalabox.emu.core.model.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * شاشة الحاويات — إنشاء/حذف/نسخ احتياطي/استعادة/إصلاح.
 * كل حاوية = عالم Windows مستقل (المكافئ لحاويات ExaGear لكن مع إدارة كاملة).
 */
class ContainersFragment : Fragment() {

    private lateinit var settings: SettingsStore
    private lateinit var adapter: ContainersAdapter
    private val containers = mutableListOf<Container>()
    private lateinit var emptyView: View

    private val restorePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { restore(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_containers, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsStore(requireContext())
        emptyView = view.findViewById(R.id.containers_empty)

        val recycler = view.findViewById<RecyclerView>(R.id.containers_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ContainersAdapter()
        recycler.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fab_add_container).setOnClickListener {
            showCreateDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // قوائم الحاويات صغيرة (عدد محدود) — notifyDataSetChanged مقبولة هنا عمداً
    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        containers.clear()
        containers.addAll(ContainerManager.list(requireContext()))
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (containers.isEmpty()) View.VISIBLE else View.GONE
    }

    /* ─────────── إنشاء حاوية ─────────── */
    private fun showCreateDialog() {
        if (!RuntimeChecks.runtimeOk(requireContext())) return
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_container, null, false)

        // (0,0) = دقة الجهاز الأصلية (تُحسب في createContainer)
        val resolutions = listOf(
            Triple(0, 0, R.string.res_native),
            Triple(800, 600, R.string.res_800x600),
            Triple(1024, 768, R.string.res_1024x768),
            Triple(1280, 720, R.string.res_1280x720),
            Triple(1280, 800, R.string.res_1280x800),
            Triple(1920, 1080, R.string.res_1920x1080)
        )
        val drivers = listOf("turnip", "virgl", "llvm")
        val driversLabels = listOf(
            getString(R.string.driver_turnip),
            getString(R.string.driver_virgl),
            getString(R.string.driver_llvm)
        )
        val dxs = listOf("cncddraw", "wined3d", "dxvk")
        val dxLabels = listOf(
            getString(R.string.dx_cncddraw),
            getString(R.string.dx_wined3d),
            getString(R.string.dx_dxvk)
        )
        val archs = listOf("win32", "win64")
        val archLabels = listOf(getString(R.string.arch_win32), getString(R.string.arch_win64))

        fun spinner(id: Int, items: List<String>): android.widget.Spinner {
            val sp = dialogView.findViewById<android.widget.Spinner>(id)
            sp.adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, items
            )
            return sp
        }
        val spRes = spinner(R.id.spin_resolution, resolutions.map { getString(it.third) })
        val spDriver = spinner(R.id.spin_driver, driversLabels)
        val spDx = spinner(R.id.spin_dxwrapper, dxLabels)
        val spArch = spinner(R.id.spin_arch, archLabels)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.container_create)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialogView.findViewById<TextView>(R.id.btn_create).setOnClickListener {
            val name = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.input_name
            ).text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            val res = resolutions[spRes.selectedItemPosition]
            dialog.dismiss()
            createContainer(name, res.first, res.second, drivers[spDriver.selectedItemPosition],
                dxs[spDx.selectedItemPosition], archs[spArch.selectedItemPosition])
        }
        dialog.show()
    }

    private fun createContainer(
        name: String, w: Int, h: Int, driver: String, dx: String, arch: String
    ) {
        val ctx = requireContext()
        // دقة الجهاز الأصلية — بالاتجاه الأفقي (المحاكي أفقية دائماً)
        var rw = w
        var rh = h
        if (rw == 0 || rh == 0) {
            val dm = ctx.resources.displayMetrics
            rw = maxOf(dm.widthPixels, dm.heightPixels)
            rh = minOf(dm.widthPixels, dm.heightPixels)
        }
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setMessage(R.string.container_creating)
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = ContainerManager.create(ctx, name, rw, rh, driver, dx, arch) { }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                result.fold(onSuccess = {
                    settings.selectedContainerId = it.id
                    Toast.makeText(ctx, R.string.container_created, Toast.LENGTH_SHORT).show()
                    refresh()
                }, onFailure = {
                    Toast.makeText(ctx, it.message ?: "", Toast.LENGTH_LONG).show()
                })
            }
        }
    }

    /* ─────────── قائمة الإجراءات ─────────── */
    private fun showMenu(v: View, c: Container) {
        val ctx = requireContext()
        val pm = PopupMenu(ctx, v)
        pm.menu.add(getString(R.string.container_backup))
        pm.menu.add(getString(R.string.container_restore))
        pm.menu.add(getString(R.string.container_reset_prefix))
        pm.menu.add(getString(R.string.delete))
        pm.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.container_backup) -> backup(c)
                getString(R.string.container_restore) -> restorePicker.launch(arrayOf("*/*"))
                getString(R.string.container_reset_prefix) -> resetPrefix(c)
                getString(R.string.delete) -> confirmDelete(c)
            }
            true
        }
        pm.show()
    }
    private fun backup(c: Container) {
        val ctx = requireContext()
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setMessage(R.string.loading).setCancelable(false).create()
        progress.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val r = ContainerManager.backup(ctx, c) { }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                Toast.makeText(ctx, if (r.isSuccess) R.string.container_backed_up else R.string.error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restore(uri: android.net.Uri) {
        val ctx = requireContext()
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setMessage(R.string.loading).setCancelable(false).create()
        progress.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val r = ContainerManager.restore(ctx, uri) { }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                Toast.makeText(ctx, if (r.isSuccess) R.string.container_restored else R.string.error, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun resetPrefix(c: Container) {
        val ctx = requireContext()
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setMessage(R.string.container_creating)
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ContainerManager.resetPrefix(ctx, c)
            withContext(Dispatchers.Main) {
                progress.dismiss()
                Toast.makeText(ctx, if (ok) R.string.container_reset_done else R.string.error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(c: Container) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.container_delete_confirm, c.name))
            .setPositiveButton(R.string.yes) { _, _ ->
                ContainerManager.delete(requireContext(), c)
                refresh()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    /* ─────────── المحوّل ─────────── */
    inner class ContainersAdapter : RecyclerView.Adapter<ContainersAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.container_name)
            val info: TextView = v.findViewById(R.id.container_info)
            val menu: TextView = v.findViewById(R.id.container_menu)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_container, parent, false))

        override fun getItemCount(): Int = containers.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = containers[pos]
            h.name.text = c.name
            h.info.text = ContainerManager.describe(c)
            h.menu.setOnClickListener { showMenu(it, c) }
            h.itemView.setOnClickListener {
                settings.selectedContainerId = c.id
                Toast.makeText(requireContext(), c.name, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** فحوصات مشتركة صغيرة */
object RuntimeChecks {
    fun runtimeOk(ctx: android.content.Context): Boolean {
        return if (com.qalabox.emu.core.RuntimeManager.isInstalled(ctx)) true else {
            Toast.makeText(ctx, R.string.runtime_not_installed, Toast.LENGTH_LONG).show()
            false
        }
    }
}
