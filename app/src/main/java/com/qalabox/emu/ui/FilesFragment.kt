package com.qalabox.emu.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qalabox.emu.R
import com.qalabox.emu.core.ContainerManager
import com.qalabox.emu.core.LogStore
import com.qalabox.emu.core.model.Container
import com.qalabox.emu.util.Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * مدير الملفات — استيراد ألعابك إلى الحاوية:
 * - استيراد ZIP (من أي تطبيق ملفات عبر SAF)
 * - استيراد مجلد كامل
 * - تصفح drive_c وحذف/إعادة تسمية/نسخ مسار
 */
class FilesFragment : Fragment() {

    private lateinit var spinner: Spinner
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var pathLabel: TextView
    private var containers: List<Container> = emptyList()
    private var currentDir: File? = null
    private val files = mutableListOf<File>()

    private val zipPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importZip(it) } }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { importFolder(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_files, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spinner = view.findViewById(R.id.spin_containers)
        recycler = view.findViewById(R.id.files_recycler)
        emptyView = view.findViewById(R.id.files_empty)
        pathLabel = view.findViewById(R.id.path_label)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = FilesAdapter()

        view.findViewById<TextView>(R.id.btn_import_zip).setOnClickListener {
            if (ensureContainer()) zipPicker.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        view.findViewById<TextView>(R.id.btn_import_folder).setOnClickListener {
            if (ensureContainer()) folderPicker.launch(null)
        }
        view.findViewById<TextView>(R.id.btn_up).setOnClickListener { goUp() }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (containers.isEmpty()) return
                val c = containers[pos]
                com.qalabox.emu.core.SettingsStore(requireContext()).selectedContainerId = c.id
                navigateTo(ContainerManager.driveC(requireContext(), c))
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        refreshContainers()
    }

    private fun ensureContainer(): Boolean {
        if (containers.isEmpty()) {
            Toast.makeText(requireContext(), R.string.game_missing_container, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /** صعود مجلداً واحداً — بلا تجاوز drive_c (جذر القرص C:) */
    private fun goUp() {
        val dir = currentDir ?: return
        val c = containers.getOrNull(spinner.selectedItemPosition) ?: return
        val driveC = ContainerManager.driveC(requireContext(), c)
        val parent = dir.parentFile
        if (parent != null && parent != dir &&
            (dir == driveC || dir.path.startsWith(driveC.path))) {
            if (dir != driveC) navigateTo(parent)
        }
    }

    // القوائم صغيرة — notifyDataSetChanged مقبولة هنا عمداً (البساطة فوق DiffUtil)
    @SuppressLint("NotifyDataSetChanged")
    private fun refreshContainers() {
        containers = ContainerManager.list(requireContext())
        val labels = containers.map { it.name }
        spinner.adapter = android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, labels
        )
        val sel = com.qalabox.emu.core.SettingsStore(requireContext()).selectedContainerId
        val idx = containers.indexOfFirst { it.id == sel }
        if (idx >= 0) spinner.setSelection(idx, false)
        if (containers.isNotEmpty()) {
            if (currentDir == null) {
                navigateTo(ContainerManager.driveC(requireContext(), containers[0]))
            }
        } else {
            // لا حاويات — أظهر الحالة المناسبة بدل "المجلد فارغ"
            currentDir = null
            files.clear()
            recycler.adapter?.notifyDataSetChanged()
            emptyView.visibility = View.VISIBLE
            emptyView.findViewById<TextView>(R.id.empty_text)?.text =
                getString(R.string.files_no_container)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun navigateTo(dir: File) {
        currentDir = dir
        pathLabel.text = windowsStylePath(dir)
        files.clear()
        try {
            dir.listFiles()?.let { list ->
                files.addAll(list.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }))
            }
        } catch (e: Exception) {
            LogStore.append("Files", "تعذر قراءة المجلد: ${e.message}")
        }
        recycler.adapter?.notifyDataSetChanged()
        emptyView.findViewById<TextView>(R.id.empty_text)?.text = getString(R.string.files_empty_dir)
        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun windowsStylePath(f: File): String {
        val c = containers.getOrNull(spinner.selectedItemPosition) ?: return f.name
        val driveC = ContainerManager.driveC(requireContext(), c)
        val rel = f.toRelativeString(driveC)
        return "C:\\" + rel.replace('/', '\\')
    }

    /* ─────────── الاستيراد ─────────── */
    private fun importZip(uri: Uri) {
        val ctx = requireContext()
        val dest = currentDir ?: return
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setMessage(getString(R.string.files_importing, "ZIP"))
            .setCancelable(false).create()
        progress.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = ctx.contentResolver.openInputStream(uri)?.let {
                Fs.unzipTo(it, dest)
            } ?: false
            LogStore.append("Files", "استيراد ZIP → ${dest.name}: $ok")
            withContext(Dispatchers.Main) {
                progress.dismiss()
                Toast.makeText(ctx, if (ok) R.string.files_imported else R.string.error, Toast.LENGTH_SHORT).show()
                navigateTo(dest)
            }
        }
    }

    private fun importFolder(uri: Uri) {
        val ctx = requireContext()
        val dest = currentDir ?: return
        val progress = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setMessage(getString(R.string.files_importing, "…"))
            .setCancelable(false).create()
        progress.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = copyDocumentTree(ctx, uri, dest)
            LogStore.append("Files", "استيراد مجلد → ${dest.name}: $ok")
            withContext(Dispatchers.Main) {
                progress.dismiss()
                Toast.makeText(ctx, if (ok) R.string.files_imported else R.string.error, Toast.LENGTH_SHORT).show()
                navigateTo(dest)
            }
        }
    }

    /** نسخ شجرة SAF (مجلد كامل) إلى مجلد الحاوية */
    private fun copyDocumentTree(ctx: android.content.Context, treeUri: Uri, destRoot: File): Boolean {
        return try {
            val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return false
            copyDocRecursive(ctx, root, destRoot)
            true
        } catch (e: Exception) {
            LogStore.append("Files", "خطأ استيراد مجلد: ${e.message}")
            false
        }
    }

    private fun copyDocRecursive(ctx: android.content.Context, doc: DocumentFile, dest: File) {
        if (doc.isDirectory) {
            val sub = File(dest, doc.name ?: "folder")
            sub.mkdirs()
            doc.listFiles().forEach { copyDocRecursive(ctx, it, sub) }
        } else {
            val name = doc.name ?: return
            val f = File(dest, name)
            ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
                f.outputStream().use { ins.copyTo(it, 1024 * 128) }
            }
        }
    }

    /* ─────────── قائمة الملف ─────────── */
    private fun showFileMenu(v: View, f: File) {
        val pm = PopupMenu(requireContext(), v)
        pm.menu.add(getString(R.string.rename))
        pm.menu.add(getString(R.string.delete))
        pm.menu.add(getString(R.string.files_copy_path))
        pm.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.rename) -> showRename(f)
                getString(R.string.delete) -> {
                    Fs.deleteRecursively(f)
                    Toast.makeText(requireContext(), R.string.files_deleted, Toast.LENGTH_SHORT).show()
                    currentDir?.let { navigateTo(it) }
                }
                getString(R.string.files_copy_path) -> {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("path", windowsStylePath(f)))
                    Toast.makeText(requireContext(), R.string.files_path_copied, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        pm.show()
    }

    private fun showRename(f: File) {
        val input = android.widget.EditText(requireContext()).apply { setText(f.name) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && f.renameTo(File(f.parentFile, newName))) {
                    Toast.makeText(requireContext(), R.string.files_renamed, Toast.LENGTH_SHORT).show()
                    currentDir?.let { navigateTo(it) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* ─────────── المحوّل ─────────── */
    inner class FilesAdapter : RecyclerView.Adapter<FilesAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.file_icon)
            val name: TextView = v.findViewById(R.id.file_name)
            val size: TextView = v.findViewById(R.id.file_size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_file, parent, false))

        override fun getItemCount(): Int = files.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = files[pos]
            h.icon.text = when {
                f.isDirectory -> "[dir]"
                f.name.endsWith(".exe", ignoreCase = true) -> "[exe]"
                else -> "[mlf]"
            }
            h.name.text = f.name
            h.size.text = if (f.isDirectory) "" else Fs.humanSize(f.length())
            h.itemView.setOnClickListener {
                if (f.isDirectory) navigateTo(f)
            }
            h.itemView.setOnLongClickListener { v ->
                showFileMenu(v, f); true
            }
        }
    }
}
