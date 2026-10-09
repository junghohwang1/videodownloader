# 동영상 다운로더 (Kotlin · Jetpack Compose)

기존 상용 APK(「동영상 다운로더」 v2.7.3)의 **기능과 화면 구성만 참고해 처음부터 새로 작성한** Android 앱입니다.
디컴파일한 코드는 사용하지 않았고, 광고·분석·추적 SDK(AdMob, AppLovin, Pangle, Moloco, Firebase 등)는 넣지 않았습니다. 벨소리 기능은 범위에서 뺐습니다.

## 기능

| 영역 | 내용 |
|---|---|
| 화면 구성 | 하단 바는 탭·진행 상태·완료 3개(원본 앱과 동일). 비공개 폴더·설정은 브라우저 메뉴(⋮)에서 연다. "탭"을 한 번 더 누르면 열린 탭 목록 |
| 내장 브라우저 | 앱을 껐다 켜도 열린 탭(주소·제목·보던 탭·뒤로가기 기록) 복원, 페이지 위 반투명 원형 다운로드 버튼(감지 개수 배지), 여러 탭(최대 10개, 링크의 새 창은 새 탭으로·스크립트 팝업 차단), 페이지 하단 고정 메뉴·배너 숨기기, 주소/검색 입력, 뒤로·앞으로·새로고침, 북마크 타일(길게 눌러 삭제), 방문 기록, 웹 영상 전체 화면, 다른 앱에서 링크 "공유"로 열기 |
| 유튜브 | 영상 페이지(watch·shorts·youtu.be, 앱 공유 포함)를 열면 NewPipe Extractor 로 화질 목록을 가져와 다운로드 창에 표시. H.264 영상 + AAC 음성을 조각 단위로 받아 MP4 로 합침(최대 1080p), 360p 단일 파일·음성만(M4A)도 가능. 다운로드를 시작할 때마다 스트림 주소를 새로 조회해 이어받기 지원 |
| 번역 | 외국어 페이지를 열면 "한국어로 번역할까요?" 안내(설정에서 끄기 가능), 메뉴의 "한국어로 번역/원문 보기". 구글 번역 웹 프록시(*.translate.goog)를 거쳐 표시 |
| 영상 감지 | WebView 네트워크 요청을 관찰(mp4/webm/mov/mkv… · m3u8) + 페이지 `<video>` 요소 주기 검사. 크기·길이·화질을 미리 조회하고, HLS 마스터의 하위 화질은 하나로 묶어 표시 |
| 다운로드 | 일반 파일은 Range 요청으로 이어받기, HLS는 최고 화질 세그먼트를 4개씩 병렬로 받고 AES-128 복호화 후 MP4로 리먹싱(실패하면 TS로 저장). 일시정지·재개·재시도, 동시 다운로드 수 제한, Wi-Fi 전용, 진행/완료 알림(포그라운드 서비스) |
| 파일 | 진행 중/완료 탭, 썸네일, 재생, 공유, 갤러리로 내보내기(Movies/VideoDownloader), 이름 변경, 비공개 폴더로 이동, 삭제 |
| 플레이어 | Media3 ExoPlayer 전체 화면 재생, 가로 전환 버튼 |
| 비공개 폴더 | 4자리 PIN(PBKDF2 해시 저장). 파일은 앱 내부 저장소에 보관해 다른 앱에서 보이지 않음. 탭을 벗어나거나 앱이 백그라운드로 가면 다시 잠김 |
| 설정 | 동시 다운로드 수, Wi-Fi 전용, 완료 알림, 검색 엔진, 방문 기록 저장/삭제, 쿠키·캐시 삭제, PIN 초기화 |

지원하지 않는 것: DRM(SAMPLE-AES, Widevine), 라이브 스트림, MSE `blob:` 스트리밍만 쓰는 사이트(유튜브는 예외로 별도 지원).

> 유튜브 지원은 NewPipe Extractor(GPL-3.0)를 사용하므로 개인 사용 전용입니다. 유튜브 이용약관상 공식 기능 외 다운로드는 허용되지 않으며, 구글 플레이 배포도 불가합니다. 유튜브가 방식을 바꾸면 `libs.versions.toml` 의 `newpipeExtractor` 버전을 올려야 합니다.

## 구조

```
app/src/main/java/com/example/videodownloader/
├─ App.kt, AppContainer.kt      앱 진입점 · 수동 DI
├─ MainActivity.kt, MainScreen.kt  하단 탭(브라우저/파일/비공개/설정)
├─ browser/   WebView 생성·감지(MediaDetection), BrowserViewModel, 화면
├─ download/  DownloadManager(큐) · HttpDownloader · HlsDownloader · HlsParser · Remuxer · DownloadService · Notifications
├─ files/     파일 화면, LibraryActions(이동/내보내기/공유)
├─ vault/     비공개 폴더 + PIN 패드
├─ settings/  설정 화면
├─ player/    PlayerActivity
├─ data/      Room(downloads/history/bookmarks), DataStore 설정
└─ util/      Storage(저장 경로·MediaStore), 포맷터
```

- 다운로드 상태는 모두 Room에 저장합니다. 앱 프로세스가 종료되면 진행 중이던 작업은 다음 실행 때 `일시정지`로 돌아갑니다.
- 일반 다운로드는 `Android/data/<패키지>/files/Movies` 에 저장하므로 저장소 권한이 필요 없습니다.

## Windows 데스크톱판

`desktop/` 에 Windows 용 앱이 있습니다(Compose Multiplatform, Android 빌드와 분리). 자세한 내용은 [desktop/README.md](desktop/README.md).

## 빌드

- JDK 17 이상(21에서 확인), Android SDK Platform 37
- AGP 9.4.1 / Gradle 9.7.1 / Kotlin 2.3.21 / Compose BOM 2026.09.00
- minSdk 29(Android 10), targetSdk 37

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # HLS 파서 단위 테스트
./gradlew :app:assembleRelease      # R8 축소 적용, 서명 설정은 직접 추가
```

`applicationId`/`namespace`는 임시 값 `com.example.videodownloader` 입니다. 배포 전에 바꾸세요.

## 이용 안내

저작권이 있는 콘텐츠는 권리자의 허락 없이 내려받거나 배포하면 안 됩니다. 각 사이트의 이용약관을 따라야 합니다.

## 친구에게 배포 (성경앱과 같은 방식)

앱의 "친구에게 앱 공유"는 파일 대신 공개 저장소의 최신 릴리스 링크와 설치 안내를 보냅니다(카카오톡은 .apk 전송을 막음).

- 다운로드 저장소: https://github.com/junghohwang1/video-downloader-download (공개)
- 링크: `releases/latest/download/videodownloader.apk` — 항상 같은 파일 이름으로 올려야 링크가 유지됩니다.
- 서명: `app/signing/appkey.jks` (debug·release 공통 고정 키). **저장소에는 올리지 않습니다**(공개되면 누구나 가짜 업데이트를 만들 수 있음). 이 파일은 따로 백업해 두세요. 키가 바뀌거나 잃어버리면 받은 사람이 앱을 지우고 다시 설치해야 합니다. 키가 없는 PC 에서는 기본 debug 키로 빌드됩니다.

새 버전 올리기:

```bash
# app/build.gradle.kts 의 versionCode·versionName 을 올린 뒤
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk videodownloader.apk
gh release create v1.0.1 videodownloader.apk -R junghohwang1/video-downloader-download --title "동영상 다운로더 1.0.1" --notes "변경 내용"
```
