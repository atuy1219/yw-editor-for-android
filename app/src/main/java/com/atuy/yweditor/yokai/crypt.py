import os
import struct


# --- 1. CRC32計算用テーブル ---
def make_crc_table():
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            if c & 1:
                c = 0xEDB88320 ^ (c >> 1)
            else:
                c >>= 1
        table.append(c)
    return table


CRC_TABLE = make_crc_table()


def calc_crc32(data: bytes):
    crc = 0xFFFFFFFF
    for byte in data:
        crc = CRC_TABLE[(crc ^ byte) & 0xFF] ^ (crc >> 8)
    return crc ^ 0xFFFFFFFF


# --- 2. 復号/暗号化アルゴリズム (ieCCode) ---
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

    # Xorshift128 初期化
    s0 = ((seed ^ (seed >> 30)) * 0x6C078965 + 1) & 0xFFFFFFFF
    s1 = ((s0 ^ (s0 >> 30)) * 0x6C078965 + 2) & 0xFFFFFFFF
    s2 = ((s1 ^ (s1 >> 30)) * 0x6C078965 + 3) & 0xFFFFFFFF
    s3 = 0x03DF95B3

    # 4096回のシャッフル (3DS版の二重参照Swapロジックを忠実再現)
    for _ in range(4096):
        t = s0 ^ ((s0 << 11) & 0xFFFFFFFF)
        s0 = s1
        s1 = s2
        s2 = s3
        s3 = (s3 ^ (s3 >> 19) ^ t ^ (t >> 8)) & 0xFFFFFFFF

        idx1 = (s3 >> 8) & 0xFF
        idx2 = s3 & 0xFF

        if idx1 != idx2:
            # 単なる box[idx1] と box[idx2] の入れ替えではなく、
            # 中に入っている「値」をインデックスとして使って入れ替える
            val1 = box[idx1]
            val2 = box[idx2]
            box[val1], box[val2] = box[val2], box[val1]

    return box


def cipher_data(data: bytes, seed: int):
    sbox = make_sbox(seed)
    out = bytearray(data)
    size = len(out)
    multiplier = 0

    for i in range(size):
        block_idx = (i >> 8) & 0xFF
        byte_idx = i & 0xFF

        if byte_idx == 0:
            multiplier = PRIMES[sbox[block_idx]]

        key_idx = (multiplier * (byte_idx + 1)) & 0xFF
        out[i] ^= sbox[key_idx]

    return bytes(out)


# --- 3. メイン処理（展開） ---
def unpack_main_bin(filepath: str, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    with open(filepath, "rb") as f:
        data = f.read()

    files_info = [
        ("head.yw", 0x40),
        ("game0.yw", 0x60),
        ("game1.yw", 0x80),
        ("game2.yw", 0xA0),
    ]

    for name, header_pos in files_info:
        name_hash, offset, size = struct.unpack_from("<III", data, header_pos)
        if size == 0:
            continue

        payload_start = 0xC0 + offset
        payload = data[payload_start : payload_start + size]

        # 実際のデータは末尾8バイト(CRC + シード)を除いたもの
        real_data_size = size - 8
        enc_data = payload[:real_data_size]

        # 末尾から本物のシード値を取得
        crc, real_seed = struct.unpack_from("<II", payload, real_data_size)

        # 復号
        dec_data = cipher_data(enc_data, real_seed)

        out_path = os.path.join(out_dir, name)
        with open(out_path, "wb") as out_f:
            out_f.write(dec_data)
        print(
            f"成功: {name} を展開・復号しました (サイズ: {real_data_size} bytes, シード: {hex(real_seed)})"
        )


# --- 4. メイン処理（再構築） ---
def pack_main_bin(in_dir: str, out_filepath: str):
    files_info = [
        ("head.yw", 0x40),
        ("game0.yw", 0x60),
        ("game1.yw", 0x80),
        ("game2.yw", 0xA0),
    ]

    header = bytearray(0xC0)
    struct.pack_into("<I", header, 0x04, 0x906E0960)  # マジックナンバー

    payloads = bytearray()
    current_offset = 0
    seeds = [0x12345678, 0x87654321, 0x11223344, 0x55667788]  # ランダムなシード値

    for idx, (name, header_pos) in enumerate(files_info):
        in_path = os.path.join(in_dir, name)
        if not os.path.exists(in_path):
            continue

        with open(in_path, "rb") as f:
            dec_data = f.read()

        seed = seeds[idx]
        enc_data = cipher_data(dec_data, seed)

        # フッタの作成 (CRC + シード)
        payload = bytearray(enc_data)
        crc = calc_crc32(enc_data)
        payload.extend(struct.pack("<II", crc, seed))

        # ヘッダ情報の更新 (ファイル名のCRC32ハッシュを書き込む)
        size = len(payload)
        name_hash = calc_crc32(name.encode("utf-8"))
        struct.pack_into("<III", header, header_pos, name_hash, current_offset, size)

        payloads.extend(payload)
        current_offset += size

    final_data = bytearray()
    final_data.extend(header)
    final_data.extend(payloads)

    # 全体のCRCを再計算して先頭に書き込む
    total_crc = calc_crc32(final_data[4:])
    struct.pack_into("<I", final_data, 0, total_crc)

    with open(out_filepath, "wb") as f:
        f.write(final_data)
    print(f"成功: {out_filepath} に暗号化・パッキングしました")


# ====== 実行テスト ======
if __name__ == "__main__":
    unpack_main_bin("main.bin", "unpacked_saves")
