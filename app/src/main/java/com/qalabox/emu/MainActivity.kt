package com.qalabox.emu

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.qalabox.emu.ui.ContainersFragment
import com.qalabox.emu.ui.FilesFragment
import com.qalabox.emu.ui.GamesFragment
import com.qalabox.emu.ui.SettingsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/** النشاط الرئيسي — تنقل بين الشاشات الأربع بأسلوب بسيط ومستقر */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            val f: Fragment = when (item.itemId) {
                R.id.nav_containers -> ContainersFragment()
                R.id.nav_files -> FilesFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> GamesFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, f)
                .commit()
            true
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_games
        }
    }
}
