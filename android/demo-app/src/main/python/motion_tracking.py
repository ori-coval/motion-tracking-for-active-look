import cv2
import numpy as np
import requests
from PIL import Image
from io import BytesIO

def process_stream(stream_url, minArea, maxArea):
    print("Starting stream from:", stream_url)
    stream = requests.get(stream_url, stream=True)
    bytes_buffer = bytes()

    prev_gray = None

    try:
        for chunk in stream.iter_content(chunk_size=1024):
            bytes_buffer += chunk
            # Search for the start (0xffd8) and end (0xffd9) of the JPEG
            start = 0
            while True:
                start = bytes_buffer.find(b'\xff\xd8', start)
                end = bytes_buffer.find(b'\xff\xd9', start)
                if start != -1 and end != -1:
                    jpg = bytes_buffer[start:end + 2]
                    bytes_buffer = bytes_buffer[end + 2:]
                    try:
                        # Convert bytes to a PIL image, then to an OpenCV format
                        img = Image.open(BytesIO(jpg))
                        frame = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)

                        # Convert the current frame to grayscale
                        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

                        centers = []  # List to store centers of detected objects

                        if prev_gray is not None:
                            # Calculate absolute difference between current frame and previous frame
                            frame_diff = cv2.absdiff(prev_gray, gray)
                            _, thresh = cv2.threshold(frame_diff, 25, 255, cv2.THRESH_BINARY)
                            dilated = cv2.dilate(thresh, None, iterations=2)
                            contours, _ = cv2.findContours(dilated, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)

                            # Draw rectangles around detected motion and calculate centers
                            for contour in contours:
                                if minArea < cv2.contourArea(contour) < maxArea:
                                    x, y, w, h = cv2.boundingRect(contour)
                                    cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 2)
                                    center_x = x + w // 2
                                    center_y = y + h // 2
                                    centers.append((center_x, center_y))

                        # Update previous frame
                        prev_gray = gray.copy()

                        # Encode processed frame back to JPEG
                        ret, buffer = cv2.imencode('.jpg', frame)
                        if ret:
                            yield buffer.tobytes(), centers
                    except IOError:
                        print("Failed to decode image, skipping frame")
                    start = 0  # Reset start to look for a new frame
                else:
                    # Move the start ahead and break if no end found
                    start = start + 2 if start != -1 else 0
                    break
    except Exception as e:
        print(f"Error during stream processing: {e}")
    finally:
        stream.close()
        print("Stream closed")
