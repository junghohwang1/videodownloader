package com.example.videodownloader.util

import android.content.Context
import android.content.Intent

/**
 * 친구에게 공유하기.
 *
 * 카카오톡은 .apk 파일 전송을 막으므로 파일 대신 공개 저장소의 최신 릴리스 링크와 설치 안내를 보낸다.
 * 릴리스에는 항상 같은 이름([APK_NAME])으로 올려 버전이 바뀌어도 링크가 그대로다.
 * 고정 서명키(app/signing/appkey.jks)로 서명한 APK 라 받은 사람도 이후 새 버전을 덮어 설치할 수 있다.
 */
object AppShare {
    const val APK_NAME = "videodownloader.apk"
    private const val REPO = "junghohwang1/video-downloader-download"

    /** 공개 저장소 최신 릴리스의 APK. */
    const val DOWNLOAD_URL = "https://github.com/$REPO/releases/latest/download/$APK_NAME"

    /** 자세한 설치 안내(공개 저장소 README). */
    const val GUIDE_URL = "https://github.com/$REPO#readme"

    private val INTRO =
        "동영상 다운로더 앱을 추천합니다.\n" +
            "웹페이지·유튜브 영상을 받아 보관하고, 탭 브라우저·번역·비공개 폴더도 쓸 수 있어요. 광고가 없습니다.\n\n" +
            "■ 받기\n" +
            DOWNLOAD_URL + "\n\n" +
            installSteps(APK_NAME) + "\n\n" +
            "■ 그래도 어려우면 자세한 안내\n" +
            GUIDE_URL

    /** 앱 추천 문구(링크 + 설치 방법)를 카톡 등으로 보낸다. */
    fun shareApp(context: Context) = share(context, INTRO, "동영상 다운로더 추천하기")

    fun sharePage(context: Context, title: String, url: String) {
        val text = if (title.isNotBlank() && title != url) "$title\n$url" else url
        share(context, text, "페이지 공유")
    }

    /** 설치 방법(안드로이드 · 삼성 갤럭시 기준). 플레이 스토어 밖 설치에서 실제로 보게 되는 문구 순서대로 적는다. */
    private fun installSteps(apkName: String): String =
        "■ 설치 방법 (안드로이드 · 삼성 갤럭시 기준)\n" +
            "플레이 스토어 앱이 아니라서 휴대폰이 몇 번 막습니다. 아래 순서대로 누르면 됩니다.\n\n" +
            "1) 위 링크를 누르면 파일($apkName)을 받습니다.\n" +
            " · 「유해할 수 있는 파일」이라고 물으면 「다운로드」 또는 「무시하고 다운로드」\n" +
            " · 카톡 안에서 안 받아지면 오른쪽 위 ⋮ > 「다른 브라우저로 열기」\n\n" +
            "2) 다 받으면 「열기」를 누릅니다. (지나쳤으면 「내 파일」 앱 > 다운로드 > $apkName)\n\n" +
            "3) 「자동 차단기가 … 차단했습니다」가 나오면\n" +
            " · 설정 > 보안 및 개인정보 보호 > 자동 차단기 > 끄기, 그다음 2)부터 다시\n\n" +
            "4) 「보안을 위해 … 이 출처의 알 수 없는 앱을 설치할 수 없습니다」가 나오면\n" +
            " · 「설정」 > 「이 출처 허용」 켜기 > 뒤로 > 「설치」\n" +
            " · 직접 켜는 곳: 설정 > 애플리케이션 > 오른쪽 위 ⋮ > 특별한 접근 > 출처를 알 수 없는 앱 설치 > 파일을 연 앱(카카오톡·인터넷·Chrome·내 파일) 켜기\n\n" +
            "5) 「Play 프로텍트」 경고가 나오면\n" +
            " · 「앱 스캔」을 물으면 「스캔하지 않고 설치」(또는 스캔 후 설치)\n" +
            " · 「안전하지 않은 앱 차단됨」이면 「세부정보 더보기」 > 「무시하고 설치」\n\n" +
            "6) 「설치」 > 「열기」로 끝. 4)에서 켠 허용은 다시 꺼도 됩니다.\n" +
            " · 자동 차단기를 다시 켜면 나중에 새 버전 설치도 막히니, 업데이트할 때 잠시 꺼 주세요."

    private fun share(context: Context, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
