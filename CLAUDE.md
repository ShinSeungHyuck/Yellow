# Yellow - 음정 연습 Android 앱

## 프로젝트 개요
음정 감지 및 피아노 연습을 위한 음악 교육 Android 앱. 실시간 마이크 음정 감지, MIDI 파싱/시각화, 노래 검색 기능 제공.

## 기술 스택
- **언어:** Kotlin (Java 17 타겟)
- **빌드:** Gradle 8.13 (Kotlin DSL `.kts`)
- **최소 SDK:** 24 (Android 7.0) / **타겟 SDK:** 34
- **UI:** Material Design 3, View Binding, Navigation Components
- **오디오:** TarsosDSP (YIN 알고리즘, AAR 라이브러리 `app/libs/`)
- **미디어:** ExoPlayer 2.19.1
- **백엔드:** Supabase (auth, storage, database)
- **HTTP:** OkHttp 4.12.0, Ktor Client
- **검색 API:** `https://r2-music-search.den1116.workers.dev`

## 프로젝트 구조
```
Yellow/
├── app/src/main/java/com/example/yellow/
│   ├── MainActivity.kt          # 메인 액티비티 (네비게이션)
│   ├── SplashActivity.kt        # 스플래시 화면
│   ├── PianoFragment.kt         # 피아노 연습 UI
│   ├── PianoRollView.kt         # MIDI 시각화 커스텀 뷰
│   ├── PitchView.kt             # 음정 시각화
│   ├── VoicePitchDetector.kt    # 실시간 음정 감지
│   ├── MidiParser.kt            # MIDI 파일 파싱
│   ├── Mp3OnsetDetector.kt      # 비트 감지
│   ├── MusicalNote.kt           # 음표 데이터 클래스
│   ├── SettingsStore.kt         # 설정 저장
│   ├── data/
│   │   ├── Song.kt              # 노래 데이터 모델
│   │   ├── FavoritesStore.kt    # 즐겨찾기 (SharedPreferences)
│   │   ├── SongSearchRepository.kt   # R2 음악 검색
│   │   └── SongCatalogRepository.kt  # 노래 카탈로그
│   └── ui/
│       ├── search/SearchFragment.kt  # 노래 검색
│       ├── library/LibraryFragment.kt # 즐겨찾기/라이브러리
│       └── settings/SettingsFragment.kt # 설정
├── app/src/main/res/            # 레이아웃, 드로어블, 값 리소스
├── app/build.gradle.kts         # 앱 빌드 설정
├── build.gradle.kts             # 루트 빌드 설정
└── settings.gradle.kts          # 모듈 설정
```

## 빌드 & 실행
```bash
# 디버그 빌드
./gradlew assembleDebug

# 테스트 실행
./gradlew test

# 앱 설치 (연결된 기기)
./gradlew installDebug
```

## 권한
- `INTERNET` - 네트워크 통신
- `RECORD_AUDIO` - 마이크 음정 감지

## 핵심 기능
1. **실시간 음정 감지** - TarsosDSP YIN 알고리즘 (22,050Hz, 1024 버퍼)
2. **MIDI 시각화** - PianoRollView 커스텀 캔버스
3. **노래 검색** - R2 API + 퍼지 매칭 (레벤슈타인 거리)
4. **즐겨찾기** - SharedPreferences JSON 저장, 키 오프셋 기억
5. **키 조옮김** - 사용자 맞춤 조옮김 지원

## 코딩 컨벤션
- 코드 주석은 한국어 사용
- View Binding 사용 (`findViewById` 사용하지 않음)
- Navigation Components로 화면 전환
- 네임스페이스: `com.example.yellow`
