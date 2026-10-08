# Puyo Puyo (뿌요뿌요) Android Studio & GitHub Actions Project

이 프로젝트는 Android Studio에서 직접 빌드하거나, **GitHub Actions 클라우드 CI/CD**를 통해 PC에 개발 도구가 없어도 웹에서 즉시 APK를 다운로드할 수 있도록 구성되어 있습니다.

## ☁️ GitHub에서 APK 자동 빌드 및 다운로드 방법 (PC 설치 불필요!)
1. GitHub에서 새 Repository 생성 (Public 또는 Private)
2. 이 압축 파일 안의 전체 파일들을 해당 GitHub 저장소에 업로드(Push)
3. GitHub 저장소의 **Actions** 탭 이동
4. **Build Android APK** 워크플로우 클릭 후 **Run workflow** 실행 (약 1~2분 소요)
5. 완료된 작업 클릭 후 하단 **Artifacts**에서 `puyo-puyo-debug-apk` 다운로드!

## 💻 Android Studio 직접 빌드 방법
1. 압축 해제 후 **Android Studio** 실행
2. **Open** 메뉴 선택 -> 압축을 푼 폴더 선택
3. 상단 메뉴 **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)** 클릭
4. 완료 후 `locate` 링크 클릭 시 `app-debug.apk` 생성 완료!
