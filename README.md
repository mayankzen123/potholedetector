# Pothole Detector

## Project Description
The Pothole Detector is an Android application designed to detect potholes using the device's accelerometer sensor. It runs as a background service, continuously monitoring for sudden vertical accelerations that indicate a pothole. Detected potholes are logged with their timestamp, severity, and location (if available), and the user is notified through vibrations and UI updates.

## Features
*   **Accelerometer-based Detection:** Utilizes the device's accelerometer to identify sudden impacts characteristic of potholes.
*   **Background Service:** Runs as a foreground service, allowing continuous detection even when the app is not actively in use.
*   **Location Tracking:** Records the GPS coordinates of detected potholes (requires location permissions).
*   **Configurable Sensitivity:** Users can adjust the detection sensitivity via a slider in the main activity.
*   **Detection History:** Logs all detected potholes to a file and displays recent detections in the app's UI.
*   **Vibration Feedback:** Provides haptic feedback upon pothole detection, with vibration intensity varying by severity.
*   **Notification System:** Displays persistent and event-based notifications to keep the user informed about the service status and detections.

## How it Works (Core Detection Flow)

<img width="3840" height="3323" alt="image" src="https://github.com/user-attachments/assets/fb393e2f-1023-4c63-8f9d-0b1edaeba794" />

## Permissions
The application requires the following permissions:
*   `android.permission.ACCESS_FINE_LOCATION`
*   `android.permission.ACCESS_COARSE_LOCATION`
*   `android.permission.VIBRATE`
*   `android.permission.POST_NOTIFICATIONS` (for Android 13 and above)

## Setup and Installation
1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/PotholeDetector.git
    ```
2.  **Open in Android Studio:**
    Open the cloned project in Android Studio.
3.  **Sync Gradle:**
    Allow Android Studio to sync the Gradle project and download any necessary dependencies.
4.  **Build and Run:**
    Run the application on an Android emulator or a physical device (Android 8.0 Oreo / API level 26 or higher is recommended).

## Usage
1.  **Grant Permissions:** Upon first launch, the app will request necessary permissions. Grant all of them for full functionality.
2.  **Adjust Sensitivity:** Use the "Sensitivity" slider to set the detection threshold. A lower threshold means higher sensitivity (more detections). You can lock the sensitivity to prevent accidental changes while the service is running.
3.  **Start Detection:** Tap the "Start Detection" button to begin monitoring for potholes. The service will run in the background, and a persistent notification will indicate its active status.
4.  **Stop Detection:** Tap the "Stop Detection" button to halt the monitoring service.
5.  **View Detections:** The main screen will display a list of recent pothole detections.
6.  **Clear Detections:** Use the "Clear Detections" button to clear the displayed list and the saved detection file.
