import matplotlib.pyplot as plt
import matplotlib.patches as patches
from pathlib import Path

def generate_state_machine_diagram(output_path: Path):
    fig, ax = plt.subplots(figsize=(10.8, 6.8), dpi=300)
    ax.set_xlim(0, 10.8)
    ax.set_ylim(0, 6.8)
    ax.axis('off')

    # Color palette
    box_face = "#F8FAFC"
    box_edge = "#1E3A8A"
    arrow_color = "#0F172A"
    text_color = "#0F172A"
    accent_green_border = "#059669"
    accent_green_bg = "#ECFDF5"

    def draw_box(x, y, w, h, title, subtitle=None, is_active=False):
        edge = accent_green_border if is_active else box_edge
        face = accent_green_bg if is_active else box_face
        rect = patches.FancyBboxPatch(
            (x - w/2, y - h/2), w, h,
            boxstyle="round,pad=0.06,rounding_size=0.14",
            facecolor=face, edgecolor=edge, linewidth=2.0,
            zorder=3
        )
        ax.add_patch(rect)
        if subtitle:
            ax.text(x, y + 0.15, title, ha='center', va='center', fontsize=11.5, fontweight='bold', color=text_color, zorder=4)
            ax.text(x, y - 0.17, subtitle, ha='center', va='center', fontsize=8.5, color="#475569", zorder=4)
        else:
            ax.text(x, y, title, ha='center', va='center', fontsize=11.5, fontweight='bold', color=text_color, zorder=4)

    def draw_arrow(x1, y1, x2, y2, label="", label_pos=0.5, label_offset=(0, 0.18), curved=0):
        conn = f"arc3,rad={curved}" if curved != 0 else "arc3,rad=0"
        arrow = patches.FancyArrowPatch(
            (x1, y1), (x2, y2),
            connectionstyle=conn,
            arrowstyle="-|>",
            mutation_scale=14,
            linewidth=1.6,
            color=arrow_color,
            zorder=2
        )
        ax.add_patch(arrow)
        if label:
            mx = x1 + (x2 - x1) * label_pos + label_offset[0]
            my = y1 + (y2 - y1) * label_pos + label_offset[1]
            ax.text(mx, my, label, ha='center', va='center', fontsize=8.5,
                    fontstyle='italic', color="#1E293B",
                    bbox=dict(boxstyle="square,pad=0.18", fc="white", ec="#CBD5E1", lw=0.6, alpha=0.94),
                    zorder=5)

    y_top = 5.4
    y_mid = 3.2
    y_bot = 0.9

    x_col1 = 1.8
    x_col2 = 5.4
    x_col3 = 9.0

    # Nodes
    # Row 1: Idle -> Scanning -> DeviceFound
    draw_box(x_col1, y_top, 2.2, 0.9, "Idle", "Початковий стан")
    draw_box(x_col2, y_top, 2.4, 0.9, "Scanning", "Пошук (~2000 мс)")
    draw_box(x_col3, y_top, 2.4, 0.9, "DeviceFound", "Пристрій виявлено")

    # Row 2: Disconnected <- Connected <- Connecting
    draw_box(x_col1, y_mid, 2.2, 0.9, "Disconnected", "Пристрій відключено")
    draw_box(x_col2, y_mid, 2.4, 0.9, "Connected", "З'єднання встановлено", is_active=True)
    draw_box(x_col3, y_mid, 2.4, 0.9, "Connecting", "Підключення (~750 мс)")

    # Row 3: Data Transfer below Connected
    draw_box(x_col2, y_bot, 3.2, 0.85, "Data Transfer", "Обмін даними (Write / Notify)")

    # Transitions
    # 1. Idle -> Scanning
    draw_arrow(x_col1 + 1.1, y_top, x_col2 - 1.2, y_top, label="startScan()", label_offset=(0, 0.22))

    # 2. Scanning -> DeviceFound
    draw_arrow(x_col2 + 1.2, y_top, x_col3 - 1.2, y_top, label="MockBleScanner: ~2 с", label_offset=(0, 0.22))

    # 3. DeviceFound -> Connecting
    draw_arrow(x_col3, y_top - 0.45, x_col3, y_mid + 0.45, label="connect(device)", label_offset=(0.95, 0))

    # 4. Connecting -> Connected
    draw_arrow(x_col3 - 1.2, y_mid, x_col2 + 1.2, y_mid, label="delay(750 мс)\nSTATUS:READY", label_offset=(0, 0.32))

    # 5. Connected -> Data Transfer & Data Transfer -> Connected
    draw_arrow(x_col2 - 0.55, y_mid - 0.45, x_col2 - 0.55, y_bot + 0.42, label="sendData()\nTX Write", label_offset=(-0.75, 0))
    draw_arrow(x_col2 + 0.55, y_bot + 0.42, x_col2 + 0.55, y_mid - 0.45, label="ACK:payload\nRX Notify", label_offset=(0.85, 0))

    # 6. Connected -> Disconnected
    draw_arrow(x_col2 - 1.2, y_mid, x_col1 + 1.1, y_mid, label="disconnect()", label_offset=(0, 0.22))

    # 7. Connecting -> Disconnected (cancellation)
    draw_arrow(x_col3 - 0.5, y_mid + 0.45, x_col1 + 0.5, y_mid + 0.45, label="скасування підключення: disconnect()", label_offset=(0, 0.28), curved=-0.22)

    # 8. Disconnected -> Scanning (repeat scan)
    draw_arrow(x_col1, y_mid + 0.45, x_col2 - 0.8, y_top - 0.45, label="повторний startScan()", label_offset=(-0.5, 0.15), curved=-0.25)

    # Title
    ax.text(5.4, 6.45, "Діаграма станів взаємодії з віртуальним BLE-пристроєм",
            ha='center', va='center', fontsize=13, fontweight='bold', color="#1E3A8A")

    plt.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Diagram saved to {output_path}")

if __name__ == "__main__":
    generate_state_machine_diagram(Path(r"C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator\tools\ble_state_machine.png"))
