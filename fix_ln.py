import sys

with open("E:/beatoraja-Android/core/src/main/java/bms/player/beatoraja/play/LaneRenderer.java", "r") as f:
    content = f.read()

old = "\t\tif (isPortrait) {\n\t\t\t// Portrait mode: notes fall horizontally (x decreases from right to left)\n\t\t\t// Rotate 270 CCW around center. Origin at (0.5, 0.5) for everything."
if old in content:
    print("Found at:", content[:content.index(old)].count('\n') + 1)
else:
    print("NOT FOUND")
    lines = content.split('\n')
    for i in range(1020, min(1050, len(lines))):
        print(f"  {i+1}: {repr(lines[i][:120])}")
