# 上游历史资料摘录

本文件保留原项目的功能和截图资料，仅用于追溯来源，不是本私用分支的安装、权限或使用说明。当前功能、构建和安全限制请以 [README.md](README.md) 为准。

来源：[giejay/Immich-Android-TV 6.2.0](https://github.com/giejay/Immich-Android-TV/tree/6.2.0)。本摘录移除了原作者募捐、服务入口和已不适用的配置说明；版权与许可保持不变。

## Immich Android TV（历史说明）

Immich is a self hosted backup solution for photos and videos. Current features include:

- Upload and view videos and photos
- Auto backup when the app is opened
- Selective album(s) for backup
- Multi-user support
- Album and Shared albums

More info here: https://github.com/immich-app/immich

上游的历史兼容性声明不等于本私用版的实测结果；当前兼容范围、权限和限制请参阅 [README.md](README.md)。

This Android TV app will allow you to view those uploaded photos and videos. Current features
include:

| Features                                                                       | Status       |
|:-------------------------------------------------------------------------------|--------------|
| Sign in by entering API key                                                    | Done         |
| Album fetching + Lazy loading                                                  | Done         |
| Showing the photos inside an album                                             | Done         |
| Showing people, random, recent or seasonal photos                              | Done         |
| Slideshow of the photos and videos with a configured interval                  | Done         |
| Setting the app as the screensaver                                             | Done         |
| Setting the albums to show in the screensaver                                  | Done         |
| Configure the interval of the screensaver                                      | Done         |
| Add generic sorting of albums and photos                                       | Done         |
| Add sorting for specific album (select last item in row and press right again) | Done         |
| Showing the 4K thumbnail instead of the full image to speed up loading         | Done         |
| Showing the EXIF data and improving the slideshow view                         | Done         |
| Configure whether to play sound with videos                                    | Done         |
| Smarter merging of portrait photos (same people, same date, same city)         | Todo         |
| Add transitions to slideshow                                                   | Done         |
| Add places/tags view                                                           | Todo         |
| Add background media playing info to screensaver                               | Todo         |
| Casting capabilities                                                           | Out of scope |
| Searching in and for albums                                                    | Todo         |
| Dependency injection with Hilt/Dagger                                          | Todo         |

## Screenshots

|                                                                                    |                                                                      |                                                                                    |
|:----------------------------------------------------------------------------------:|:--------------------------------------------------------------------:|:----------------------------------------------------------------------------------:|
|        ![Alt text](/screenshots/homescreen-1.png?raw=true "Album overview")        |      ![Alt text](/screenshots/photos.png?raw=true "All photos")      |      ![Alt text](/screenshots/sorting-options.png?raw=true "Sorting options")      |
|         ![Alt text](/screenshots/home-edit.png?raw=true "Edit homescreen")         | ![Alt text](/screenshots/settings-view.png?raw=true "View settings") | ![Alt text](/screenshots/settings-screensaver.png?raw=true "Screensaver settings") |
| ![Alt text](/screenshots/screensaver-portrait.png?raw=true "Screensaver portrait") |        ![Alt text](/screenshots/people.png?raw=true "People")        |             ![Alt text](/screenshots/seasonl.png?raw=true "Seasonal")              |
