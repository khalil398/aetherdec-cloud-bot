with open(r"c:\Users\moham\Documents\CREATED PROGRAMMS WITH AI\Antigravity\AetherDex\app\src\main\java\com\aetherdex\app\MainActivity.kt", "r", encoding="utf-8") as f:
    lines = f.readlines()

depth = 0
in_str = False
in_block_comment = False

for idx, line in enumerate(lines):
    line_num = idx + 1
    old_depth = depth
    i = 0
    while i < len(line):
        ch = line[i]
        if not in_str and not in_block_comment:
            if ch == '/' and i + 1 < len(line) and line[i+1] == '/':
                break
            elif ch == '/' and i + 1 < len(line) and line[i+1] == '*':
                in_block_comment = True
                i += 1
            elif ch == '"':
                in_str = True
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
        elif in_block_comment:
            if ch == '*' and i + 1 < len(line) and line[i+1] == '/':
                in_block_comment = False
                i += 1
        elif in_str:
            if ch == '\\':
                i += 1
            elif ch == '"':
                in_str = False
        i += 1

    if old_depth == 1 and depth == 2 and ("fun " in line or "class " in line or "onCreate" in line):
        print(f"Function start at line {line_num}: {line.strip()[:60]}")
    if old_depth == 2 and depth == 1:
        print(f"Function end at line {line_num}: {line.strip()[:60]}")
