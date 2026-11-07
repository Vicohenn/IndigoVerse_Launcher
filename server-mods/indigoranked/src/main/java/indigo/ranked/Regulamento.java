package indigo.ranked;

import java.util.ArrayList;
import java.util.List;

/** Um arquivo em config/indigoranked/regulamentos/<id>.json. */
public final class Regulamento {
    public String nome = "";
    public String descricao = "";
    /** "singles" ou "doubles". */
    public String formato = "singles";
    public int minPokemon = 3;
    public int maxPokemon = 6;
    /** Faixa da Pokédex nacional permitida (0 = sem limite). Ex.: 1 a 151 = Kanto. */
    public int pokedexMin = 0;
    public int pokedexMax = 0;
    /** Se tiver nomes aqui, só essas espécies podem (ex. "pikachu"). */
    public List<String> especiesPermitidas = new ArrayList<>();
    public List<String> especiesProibidas = new ArrayList<>();
    /** Grupos proibidos pelos rótulos do Cobblemon: legendary, mythical, ultra_beast, paradox. */
    public List<String> gruposProibidos = new ArrayList<>();
    /** Quantos lendários restritos (rótulo "restricted", ex. Mewtwo, Kyogre) cada time pode ter. */
    public int maxRestritos = 0;
    public boolean clausulaEspecie = true;
    public boolean clausulaItem = true;
    /** Ids de itens proibidos (ex. "cobblemon:choice_scarf"). */
    public List<String> itensProibidos = new ArrayList<>();
    /** Nomes de golpes proibidos, como o Cobblemon escreve (ex. "fissure"). */
    public List<String> golpesProibidos = new ArrayList<>();
    /** Só informativo: a mecânica da temporada (Mega, Z, Dynamax, Terastal). */
    public String mecanica = "";
    /** Regras do Showdown repassadas ao Cobblemon (avançado; deixe vazio se não souber). */
    public List<String> regrasShowdown = new ArrayList<>();

    static Regulamento exemplo(String nome, String desc, String formato, int min, int max, int dexMin, int dexMax,
                               List<String> grupos, int restritos, String mecanica) {
        Regulamento r = new Regulamento();
        r.nome = nome; r.descricao = desc; r.formato = formato; r.minPokemon = min; r.maxPokemon = max;
        r.pokedexMin = dexMin; r.pokedexMax = dexMax; r.gruposProibidos = new ArrayList<>(grupos);
        r.maxRestritos = restritos; r.mecanica = mecanica;
        return r;
    }

    boolean duplas() { return "doubles".equalsIgnoreCase(formato) || "duplas".equalsIgnoreCase(formato); }
}
