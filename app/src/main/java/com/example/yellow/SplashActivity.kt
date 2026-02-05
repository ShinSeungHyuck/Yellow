package com.example.yellow

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity(R.layout.activity_splash) {

    companion object {
        private const val MIN_SPLASH_MS = 650L // 로고/로딩 화면 최소 노출
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 여기서 "가벼운 로컬 초기화" 정도만 권장 (즐겨찾기/설정 로드 등)
        lifecycleScope.launch {
            delay(MIN_SPLASH_MS)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
