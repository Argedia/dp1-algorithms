import random

# ==========================
# Archivos de entrada/salida
# ==========================
PLANES_FILE = "planes_vuelos.txt"
ORDENES_FILE = "ordenes.txt"

# ==========================
# Restricciones
# ==========================
EXCLUDED_DEST = {"SPIM", "EBCI", "UBBB"}  # Destinos que no se permiten

# ==========================
# Funciones auxiliares
# ==========================

def parse_planes(file_path):
    """Lee el archivo de planes de vuelo y devuelve los destinos válidos."""
    destinos = []
    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or "-" not in line:
                continue
            parts = line.split("-")
            if len(parts) >= 2:
                destino = parts[1]
                if destino not in EXCLUDED_DEST:
                    destinos.append(destino)
    return list(set(destinos))  # quitar duplicados


def generar_pedido(destinos_validos):
    """Genera un pedido con formato dd-hh-mm-dest-###-IdClien"""
    dd = str(random.choice([1, 4, 12, 24])).zfill(2)        # días
    hh = str(random.randint(1, 23)).zfill(2)                # horas
    mm = str(random.choice([1, 8, 14, 25, 30, 45, 52, 59])).zfill(2)  # minutos
    dest = random.choice(destinos_validos)                  # destino válido
    cantidad = str(random.randint(1, 999)).zfill(3)         # cantidad productos
    id_cliente = str(random.randint(1, 9999999)).zfill(7)   # id cliente
    return f"{dd}-{hh}-{mm}-{dest}-{cantidad}-{id_cliente}"


def generar_ordenes(num_ordenes=50):
    """Genera archivo de órdenes basado en planes de vuelo."""
    destinos_validos = parse_planes(PLANES_FILE)

    if not destinos_validos:
        print("No se encontraron destinos válidos en planes_vuelo.txt")
        return

    with open(ORDENES_FILE, "w", encoding="utf-8") as f:
        for _ in range(num_ordenes):
            pedido = generar_pedido(destinos_validos)
            f.write(pedido + "\n")

    print(f"Archivo {ORDENES_FILE} generado con {num_ordenes} pedidos.")



if __name__ == "__main__":
    generar_ordenes(200)  