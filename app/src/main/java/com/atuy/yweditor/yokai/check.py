import os
import struct


# --- 暗号化・復号アルゴリズム ---
def generate_primes(count):
    primes = []
    num = 3
    while len(primes) < count:
        is_prime = True
        for i in range(2, int(num**0.5) + 1):
            if num % i == 0:
                is_prime = False
                break
        if is_prime:
            primes.append(num)
        num += 2
    return primes


PRIMES = generate_primes(256)


def make_sbox(seed: int):
    box = list(range(256))
    if seed == 0:
        return box

    s0 = ((seed ^ (seed >> 30)) * 0x6C078965 + 1) & 0xFFFFFFFF
    s1 = ((s0 ^ (s0 >> 30)) * 0x6C078965 + 2) & 0xFFFFFFFF
    s2 = ((s1 ^ (s1 >> 30)) * 0x6C078965 + 3) & 0xFFFFFFFF
    s3 = 0x03DF95B3

    for _ in range(4096):
        t = s0 ^ ((s0 << 11) & 0xFFFFFFFF)
        s0 = s1
        s1 = s2
        s2 = s3
        s3 = (s3 ^ (s3 >> 19) ^ t ^ (t >> 8)) & 0xFFFFFFFF

        idx1 = (s3 >> 8) & 0xFF
        idx2 = s3 & 0xFF
        if idx1 != idx2:
            val1, val2 = box[idx1], box[idx2]
            box[val1], box[val2] = box[val2], box[val1]
    return box


def cipher_data(data: bytes, seed: int):
    sbox = make_sbox(seed)
    out = bytearray(data)
    multiplier = 0
    for i in range(len(out)):
        if (i & 0xFF) == 0:
            multiplier = PRIMES[sbox[i >> 8 & 0xFF]]
        out[i] ^= sbox[(multiplier * ((i & 0xFF) + 1)) & 0xFF]
    return bytes(out)


# --- 読み取り処理 ---
def dump_yokai_data(filepath):
    if not os.path.exists(filepath):
        print(f"[エラー] {filepath} が見つかりません。")
        return

    with open(filepath, "rb") as f:
        data = f.read()

    # game0.yw のヘッダ位置
    header_pos = 0x60
    name_hash, offset, size = struct.unpack_from("<III", data, header_pos)

    if size == 0:
        print("[エラー] game0.yw のデータが存在しません。")
        return

    payload_start = 0xC0 + offset
    payload = data[payload_start : payload_start + size]
    real_data_size = size - 8
    enc_data = payload[:real_data_size]
    crc, real_seed = struct.unpack_from("<II", payload, real_data_size)

    # 復号
    dec_data = cipher_data(enc_data, real_seed)

    # 推測した開始アドレスとサイズ
    YOKAI_START = 0x1D40
    YOKAI_SIZE = 0x7C  # 124バイト

    print("=== 妖怪 個体値 ダンプ ===")
    found_count = 0

    for i in range(240):  # 最大240体
        base = YOKAI_START + i * YOKAI_SIZE
        if base + YOKAI_SIZE > len(dec_data):
            break

        yokai_id = struct.unpack_from("<I", dec_data, base + 0x04)[0]

        if yokai_id != 0 and yokai_id < 0xFFFFFFFF:
            # ニックネームの読み取り
            raw_name = dec_data[base + 0x08 : base + 0x08 + 36].split(b"\x00")[0]
            try:
                name = raw_name.decode("utf-8")
            except:
                name = "(文字化け)"

            level = dec_data[base + 0x74]

            # 個体値・性格補正の読み取り
            iv_a = struct.unpack_from("<BBBBB", dec_data, base + 0x60)
            iv_b = struct.unpack_from("<BBBBB", dec_data, base + 0x65)
            cb = struct.unpack_from("<BBBBB", dec_data, base + 0x6A)

            # 個体値BをB1とB2に分割 (下位4ビット=B1, 上位4ビット=B2)
            iv_b1 = [val & 0x0F for val in iv_b]
            iv_b2 = [val >> 4 for val in iv_b]

            print(f"[{found_count + 1}体目] 名前: {name} (ID: 0x{yokai_id:08X})")
            print(f"  - レベル: {level}")
            print(
                f"  - IVA   : HP={iv_a[0]:2d}, 力={iv_a[1]:2d}, 妖={iv_a[2]:2d}, 守={iv_a[3]:2d}, 速={iv_a[4]:2d}"
            )
            print(
                f"  - IVB_1 : HP={iv_b1[0]:2d}, 力={iv_b1[1]:2d}, 妖={iv_b1[2]:2d}, 守={iv_b1[3]:2d}, 速={iv_b1[4]:2d}"
            )
            print(
                f"  - IVB_2 : HP={iv_b2[0]:2d}, 力={iv_b2[1]:2d}, 妖={iv_b2[2]:2d}, 守={iv_b2[3]:2d}, 速={iv_b2[4]:2d}"
            )
            print(
                f"  - CB    : HP={cb[0]:2d}, 力={cb[1]:2d}, 妖={cb[2]:2d}, 守={cb[3]:2d}, 速={cb[4]:2d}"
            )
            print("-" * 50)

            found_count += 1
            if found_count >= 70:  # 出力が長くなりすぎないように70体で止める
                break

    if found_count == 0:
        print("[!] 妖怪が見つかりませんでした。")
    else:
        print(f"合計 {found_count} 体の妖怪データを読み込みました。")


if __name__ == "__main__":
    dump_yokai_data("main.bin")
