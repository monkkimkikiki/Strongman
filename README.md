# 벌크업 노트 APK 프로젝트

웹앱(`app/src/main/assets/index.html`)을 안드로이드 앱 껍데기(WebView)에 넣은 프로젝트예요.
아직 한 번도 빌드/설치해 보지 않은 상태라서, 처음 빌드할 때 오류가 날 수 있어요.

## 방법 A. GitHub에서 빌드 (설치 프로그램 필요 없음)
1. GitHub에서 새 저장소를 만들어요 (Private 추천).
2. 이 폴더의 파일을 전부 올려요. `.github` 폴더가 빠지기 쉬워요.
   빠졌다면 저장소에서 Add file > Create new file 로 `.github/workflows/build.yml` 을 만들고 내용을 붙여 넣어요.
3. 저장소의 Actions 탭 > "Build APK" > Run workflow 를 눌러요. 3~5분 걸려요.
4. 끝나면 저장소 오른쪽 Releases 의 `벌크업 노트 APK` 에서 `bulknote.apk` 를 바로 받을 수 있어요.
5. 폰으로 옮겨 설치해요. "출처를 알 수 없는 앱 설치"를 허용해야 해요.

## 방법 B. Android Studio
폴더를 열고 Build > Build APK(s). 결과: `app/build/outputs/apk/debug/app-debug.apk`

## 알아둘 점
- 데이터는 이 폰의 앱 안에만 저장돼요. claude.ai 쪽 웹앱 기록과는 연결되지 않아요.
- 앱을 지우면 기록도 같이 지워져요.
- 글꼴은 인터넷이 있을 때만 내려받아요. 오프라인이면 기본 글꼴로 보여요.
- 웹앱을 고친 뒤에는 `app/src/main/assets/index.html` 을 바꿔서 다시 빌드해야 해요.
- debug 서명 APK라서 개인용으로만 쓰세요.
