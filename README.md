# TokiSync 뷰어 (Android)

[tokiSync](https://github.com/pray4skylark/tokiSync) 웹 뷰어(Vue)를 **네이티브 안드로이드 앱**(Kotlin + Jetpack Compose)으로 옮긴 것입니다.
기존 **GAS 서버(`TokiSync_Server_Bundle.gs`)를 그대로 사용**하며 서버 쪽 수정은 필요 없습니다.

## 기능

| 구분 | 내용 |
|---|---|
| 서버 연결 | GAS 배포 URL(또는 ID) · 루트 폴더 ID · API Key 입력, 연결 테스트 (`get_server_info`) |
| 라이브러리 | `view_get_library` (continuationToken 페이지네이션 포함), 분류 탭, 검색, 정렬(최근 읽음/업데이트/이름), 당겨서 새로고침, 오프라인용 목록 캐시 |
| 표지 | `thumbnailId` → Drive API(토큰)로 받아 캐시, 실패 시 GAS 청크 API |
| 회차 목록 | `view_get_books_by_cache` 빠른 경로 → `view_get_books`, 읽음/최근 읽음/진행률 표시, 이어 보기 |
| 다운로드 | ① `view_get_token` 토큰으로 Drive 직접 다운로드(빠름) ② 실패 시 `view_get_chunk` 병렬 청크(웹 뷰어 방식) |
| 오프라인 | 받은 회차는 기기에 보관(용량 한도 초과 시 오래된 순 자동 삭제), 회차별/작품별 받기·삭제 |
| 웹툰 | 세로 스크롤, 핀치 줌·더블탭 줌, 화면 폭 기준 디코딩(긴 컷 메모리 보호) |
| 만화 | 페이지 넘김(HorizontalPager), 좌/우 탭 영역, 오른쪽→왼쪽 모드, 페이지별 줌 |
| 소설(EPUB) | OPF spine 순서로 본문 추출, 삽화 표시, 라이트/세피아/다크, 글자 크기·줄 간격, 위/아래 탭으로 넘김 |
| 공통 | 볼륨키 넘김, 위치 슬라이더, 이전/다음 화, 다음 화 미리 받기, 화면 꺼짐 방지, 몰입 모드 |
| 읽기 기록 | Drive `read_history.json` 과 동기화 (`view_history_get/save`), 웹 뷰어와 같은 병합 규칙(episodeId별 최신 `lastReadAt`) → **웹 ↔ 앱 이어 읽기** |

보기 방식은 분류 이름으로 자동 결정됩니다 (`Webtoon`→스크롤, `Manga`→페이지, `Novel`→소설). 리더 상단 버튼으로 바꾸면 작품별로 기억합니다.

## 빌드

### Android Studio
1. Android Studio (Ladybug 이상)에서 이 폴더를 **Open**
2. Gradle 동기화가 끝나면 ▶ Run (기기 USB 디버깅 또는 에뮬레이터)

### 명령줄
```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 디버그 키로 서명된 release APK (개인 설치용)
```
JDK 17 이상과 Android SDK(35)가 필요합니다. `local.properties` 에 `sdk.dir` 을 지정하거나 `ANDROID_HOME` 을 설정하세요.

### GitHub Actions
이 폴더를 GitHub 저장소로 올리면 `.github/workflows/build-apk.yml` 이 release APK를 빌드해 **Actions → Artifacts** 에 올립니다 (수동 실행: *Run workflow*).

## 처음 설정

1. 앱 실행 → 설정 화면
2. UserScript/웹 뷰어에 넣은 것과 같은 값 입력
   - GAS 배포 URL: `https://script.google.com/macros/s/XXXX/exec` (ID만 넣어도 됨)
   - 루트 폴더 ID
   - API Key (GAS 스크립트 속성 `API_KEY`)
3. **연결 테스트** → **저장**

## 구조

```
app/src/main/java/com/tokisync/viewer/
├─ TokiApp.kt                 Application, Coil 이미지 로더 설정
├─ data/
│  ├─ GasClient.kt            GAS API (useGAS.js 대응)
│  ├─ EpisodeStore.kt         다운로드·보관·압축 해제·EPUB 파싱 (useFetcher.js 대응)
│  ├─ HistoryStore.kt         읽기 기록 + Drive 동기화
│  ├─ ThumbnailRepo.kt        표지
│  ├─ LocalCache.kt           목록 오프라인 캐시
│  ├─ SettingsStore.kt        연결 정보·뷰어 설정
│  └─ Models.kt
├─ ui/
│  ├─ MainActivity.kt         진입점, 볼륨키, 화면 전환
│  ├─ AppViewModel.kt
│  ├─ screens/                설정 · 라이브러리 · 회차 목록
│  └─ reader/                 리더(스크롤/페이지/소설), 핀치 줌
└─ util/                      자연 정렬, XHTML→문단, 용량 표기
```

## 참고 / 제한

- 지원 형식은 웹 뷰어와 같이 **CBZ / ZIP / EPUB** 입니다. 폴더형 회차는 지원하지 않습니다.
- Drive 직접 다운로드는 GAS 배포자 계정의 OAuth 토큰(`view_get_token`)을 사용합니다. 막혀 있으면 설정에서 끄거나 자동으로 GAS 청크 방식으로 넘어갑니다.
- 웹 뷰어의 자동 여백 자르기, 2쪽 펼침 보기, 메타데이터·표지 편집, Kavita 이전 기능은 포함하지 않았습니다.
- 소설 위치(markerIndex)는 앱에서 문단 번호로 저장하므로, 소설에 한해 웹 뷰어와 위치가 정확히 일치하지 않을 수 있습니다. (이미지 회차는 이미지 번호로 동일)
