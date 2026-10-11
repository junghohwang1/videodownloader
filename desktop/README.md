# 동영상 다운로더 — Windows 데스크톱판

Android 앱(`../app`)의 다운로드 엔진(HTTP 이어받기·HLS·유튜브)을 그대로 옮겨 Compose Multiplatform(Desktop)으로 만든 Windows 앱입니다.
Android 빌드와 분리된 독립 Gradle 프로젝트라 Android SDK 없이 빌드됩니다.

## 기능

| 영역 | 내용 |
|---|---|
| 내장 브라우저 | Android 판과 같은 구성: 주소/검색창(뒤로·앞으로·새로고침·⋮ 메뉴), 홈 화면 북마크 타일(우클릭 삭제·추가), 페이지 위 반투명 원형 다운로드 버튼(감지 개수 배지) → 화질 카드 다운로드 창(닫기·이름 변경·다운로드). 영상 링크·유튜브 썸네일·플레이어 위에서 **우클릭 → "이 영상 다운로드"**. 로그인·쿠키 유지, 다운로드에 브라우저 쿠키 사용 |
| 영상 감지 | 유튜브는 주소(영상 ID)가 바뀔 때마다 화질 목록 조회, 그 밖의 사이트는 네트워크 요청(mp4/webm/m3u8…)과 페이지 `<video>` 요소를 3초마다 검사 |
| 주소 분석 | 주소 붙여넣기(버튼 한 번에 붙여넣기+분석) → 유튜브는 화질 목록, m3u8 은 화질별 선택지, 영상 파일은 크기 확인, 일반 웹페이지는 HTML 안의 `og:video`·`<video>`·`<source>`·mp4/m3u8 주소(JSON 이스케이프 포함)를 찾아 보여줌. 페이지에 유튜브가 박혀 있으면 유튜브로 처리 |
| 유튜브 | NewPipe Extractor 로 H.264+AAC 를 조각 단위로 받아 MP4 로 합침(최대 1080p), 360p 단일 파일, 음성만(M4A). 시작할 때마다 스트림 주소를 새로 조회해 이어받기 |
| 다운로드 | 일반 파일 Range 이어받기, HLS 세그먼트 4개 병렬·AES-128 복호화 후 MP4 변환(실패 시 TS). 일시정지·재개·재시도·모두 재개/일시정지, 동시 다운로드 수 제한, 속도·남은 시간 표시 |
| 완료 | 검색, 열기(기본 플레이어), 탐색기에서 보기, 이름 바꾸기, 원본 주소 열기, 삭제(파일 포함/목록만). 우클릭 메뉴 |
| 트레이 | 창을 닫으면 트레이로 내려 다운로드 계속(설정에서 끄기), 완료 알림 |
| 설정 | 저장 폴더(기본 `동영상\VideoDownloader`), 동시 다운로드 수, 알림, 트레이, ffmpeg 상태·설치 |

### Android 판과 다른 점

| Android | Windows |
|---|---|
| Room DB | `%LOCALAPPDATA%\VideoDownloader\downloads.json` (원자적 저장) |
| DataStore | `settings.json` |
| MediaMuxer / Media3 Transformer | ffmpeg(`-c copy`, 재인코딩 없음). 처음 필요할 때 자동으로 `%LOCALAPPDATA%\VideoDownloader\ffmpeg` 에 내려받음(약 110MB, 한 번만). PATH 에 있으면 그것을 씀 |
| ExoPlayer | Windows 기본 플레이어로 열기 |
| 포그라운드 서비스·알림 | 트레이 아이콘·트레이 알림 |
| WebView | Windows 기본 **Microsoft Edge WebView2** 를 JNA(COM)로 직접 띄움(`webview2/`). H.264/AAC 영상 재생 가능, 따로 내려받는 구성 요소 없음. 쿠키·캐시는 `%LOCALAPPDATA%\VideoDownloader\webview2-data`. WebView2 런타임이 없는 PC 는 안내 문구와 설치 링크를 보여 줌 |
| 하단 시트 | 별도 창(웹페이지가 OS 창이라 그 위에 그리려면 창이어야 함). 같은 이유로 다운로드 버튼도 작은 팝업 창 |

## 빌드

- JDK 17 이상(21에서 확인). Android SDK 불필요.

```bash
cd desktop
./gradlew run                       # 개발 실행
./gradlew run --args="https://..."  # 내장 브라우저에서 주소를 열고 시작(설치본: VideoDownloader.exe <주소>)
./gradlew test                      # 단위 테스트(HLS 파서, 주소 분석, 유튜브 ID)
E2E_TEST=1 ./gradlew test --tests '*EndToEndTest*'   # 실제 사이트로 웹페이지·유튜브·HLS 다운로드 확인
./gradlew packageReleaseExe         # build/compose/binaries/main-release/exe/VideoDownloader-1.0.0.exe
./gradlew packageReleaseMsi         # build/compose/binaries/main-release/msi/VideoDownloader-1.0.0.msi
./gradlew createReleaseDistributable  # 설치 없이 실행하는 폴더(app/VideoDownloader/VideoDownloader.exe)
```

설치 파일에는 Java 런타임이 포함되어 있어 PC 에 Java 가 없어도 실행됩니다(약 100MB). 사용자 단위 설치라 관리자 권한이 필요 없습니다.
MSI(WiX)는 코드페이지 1252 라 설치 메타데이터(설명·시작 메뉴 폴더)는 영문입니다. 앱 화면은 한글입니다.

## 다음 단계

- 여러 탭, 방문 기록, 페이지 번역(Android 판에 있는 나머지 브라우저 기능)
- 비공개 폴더(PIN), 자동 업데이트 확인, 코드 서명

## 구현 메모

- Skia 의 Direct3D 렌더러는 투명 팝업 창(다운로드 버튼)을 그리다 충돌해서 `skiko.renderApi=OPENGL` 로 고정했다.
- `compose.layers.type=WINDOW`: 팝업·메뉴를 별도 창으로 띄워 웹페이지(WebView2) 창 위에 보이게 한다.
- WebView2 는 만든 스레드(STA)에서만 다룰 수 있어 메시지 루프를 도는 전용 스레드(`WebView2Host`)에서 모든 호출을 처리한다. vtable 순서는 SDK `WebView2.h`(1.0.4191.47) 기준이며, 환경 생성용 `WebView2Loader.dll`(Microsoft BSD 라이선스)을 리소스에 넣어 두었다.
- 탭마다 웹뷰가 하나씩이고 모두 같은 부모 창(Canvas) 안에 겹쳐 두며, 보고 있는 탭만 보이게 한다.

## 이용 안내

저작권이 있는 콘텐츠는 권리자의 허락 없이 내려받거나 배포하면 안 됩니다. 각 사이트의 이용약관을 따라야 합니다.
유튜브 지원은 NewPipe Extractor(GPL-3.0)를 사용하므로 개인 사용 전용입니다.
