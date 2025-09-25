import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import statsmodels.api as sm
from statsmodels.formula.api import ols

# ==============================
# 1. Ingresar datos
# ==============================
data = {
    "Algoritmo": ["ACO"]*20 + ["GA"]*20,
    "Pedidos Totales": [50]*5 + [100]*5 + [150]*5 + [200]*5 +
                       [50]*5 + [100]*5 + [150]*5 + [200]*5,
    "Pedidos Entregados": [
        50,50,50,50,50,100,100,100,100,100,133,136,135,136,135,155,159,166,167,168,
        50,50,50,50,50,100,100,100,100,100,150,148,149,150,149,184,185,184,185,183
    ],
    "% Entregados a Tiempo": [
        100,100,100,100,100,100,100,100,100,100,89.3,91.3,90,90.7,90,77.5,79.5,83,83.5,84,
        100,100,100,100,100,100,100,100,100,100,100,98.67,99.33,100,99.33,92,92.5,92,92.5,91.5
    ],
    "Tiempo (s)": [
        43.774,43.006,41.858,42.637,43.497,90.071,90.759,91.8,90.861,91.507,
        116.328,115.878,121.776,130.293,130.293,147.499,164.671,164.393,165.047,168.555,
        77,80,53,59,63,184,155,189,188,185,219,243,220,207,222,514,546,541,552,387
    ],
    "Fitness": [
        26351,26351,26351,26351,26351,52140,52140,52140,52140,52140,
        49858,48318,48042,49406,47802,13193,19489,32717,42545,40909,
        52.8,52.55,52.74,52.73,52.71,102.4,102.35,102.36,102.38,102.35,
        151.95,147,149.49,151.98,149.49,161.76,162.8,161.75,164.26,159.31
    ]
}

df = pd.DataFrame(data)

# ==============================
# 2. Resumen estadístico
# ==============================
summary = df.groupby(["Algoritmo", "Pedidos Totales"]).agg(
    pedidos_promedio=("Pedidos Entregados", "mean"),
    pct_promedio=("% Entregados a Tiempo", "mean"),
    tiempo_promedio=("Tiempo (s)", "mean"),
    tiempo_std=("Tiempo (s)", "std"),
    fitness_promedio=("Fitness", "mean"),
    fitness_std=("Fitness", "std")
).reset_index()

print(summary)

# ==============================
# 3. Gráficos comparativos
# ==============================
sns.set(style="whitegrid")

# % entregados
plt.figure(figsize=(8,5))
sns.lineplot(data=df, x="Pedidos Totales", y="% Entregados a Tiempo", hue="Algoritmo", marker="o")
plt.title("% pedidos entregados a tiempo")
plt.ylim(70,105)
plt.show()

# Tiempo de ejecución
plt.figure(figsize=(8,5))
sns.lineplot(data=df, x="Pedidos Totales", y="Tiempo (s)", hue="Algoritmo", marker="o")
plt.title("Tiempo de ejecución por número de pedidos")
plt.show()

# Fitness
plt.figure(figsize=(8,5))
sns.lineplot(data=df, x="Pedidos Totales", y="Fitness", hue="Algoritmo", marker="o")
plt.title("Fitness promedio")
plt.show()

# ==============================
# 4. Ejemplo ANOVA en tiempo de ejecución
# ==============================
model = ols('Q("Tiempo (s)") ~ C(Algoritmo) * Q("Pedidos Totales")', data=df).fit()
anova_table = sm.stats.anova_lm(model, typ=2)
print("\n=== ANOVA resultados ===")
print(anova_table)


# ==============================
# Fitness con barras de error (std)
# ==============================
plt.figure(figsize=(8,5))

# Agrupamos datos para promedio + std
fitness_stats = df.groupby(["Algoritmo","Pedidos Totales"])["Fitness"].agg(["mean","std"]).reset_index()

# Gráfico con error bars
for alg in fitness_stats["Algoritmo"].unique():
    subset = fitness_stats[fitness_stats["Algoritmo"] == alg]
    plt.errorbar(subset["Pedidos Totales"], subset["mean"], yerr=subset["std"],
                 label=alg, marker="o", capsize=5)

plt.title("Fitness promedio ± desviación estándar")
plt.xlabel("Pedidos Totales")
plt.ylabel("Fitness")
plt.legend()
plt.grid(True, linestyle="--", alpha=0.6)
plt.show()
# ==============================