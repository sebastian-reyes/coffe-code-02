package com.coffeecode.mock.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.coffeecode.mock.chaos.ChaosEngine;

/**
 * "Base de datos" del core bancario simulado. Los datos se generan de forma determinista a partir
 * del clientId (misma semilla, mismos datos), así las dos APIs reciben exactamente lo mismo.
 *
 * <p>Existen los clientes {@code C0001}..{@code C9999}, excepto {@code C0404}.
 */
@Component
public class CoreBankingData {

    private static final Pattern CLIENT_ID = Pattern.compile("^C\\d{4}$");
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 10, 1);

    private static final String[] FIRST_NAMES = {"Ana", "Luis", "María", "Carlos", "Lucía", "Jorge", "Valentina", "Andrés"};
    private static final String[] LAST_NAMES = {"Torres", "Gómez", "Rodríguez", "Martínez", "Rojas", "Herrera", "Castro", "Vargas"};
    private static final String[] SEGMENTS = {"RETAIL", "PREMIUM", "PRIVATE", "SME"};
    private static final String[] MERCHANTS = {"Supermercado", "Gasolinera", "Transferencia recibida", "Pago nómina",
            "Restaurante", "Farmacia", "Pago servicios", "Retiro cajero"};

    public boolean exists(String clientId) {
        return CLIENT_ID.matcher(clientId).matches()
                && !"C0000".equals(clientId)
                && !ChaosEngine.NOT_FOUND_ID.equals(clientId);
    }

    public Optional<Client> findClient(String clientId) {
        if (!exists(clientId)) {
            return Optional.empty();
        }
        Random random = randomFor(clientId, 1);
        String fullName = pick(random, FIRST_NAMES) + " " + pick(random, LAST_NAMES);
        String document = "***" + (1000 + random.nextInt(9000));
        LocalDate since = REFERENCE_DATE.minusDays(random.nextInt(3650));
        return Optional.of(new Client(clientId, fullName, pick(random, SEGMENTS), document, since));
    }

    public Optional<Balance> findBalance(String clientId) {
        if (!exists(clientId)) {
            return Optional.empty();
        }
        Random random = randomFor(clientId, 2);
        String currency = random.nextInt(10) == 0 ? "USD" : "COP";
        BigDecimal ledger = money(random, currency.equals("USD") ? 50_000 : 50_000_000);
        BigDecimal available = ledger.subtract(money(random, currency.equals("USD") ? 500 : 500_000)).max(BigDecimal.ZERO);
        String accountNumber = "0012-" + clientId.substring(1) + "-" + (100000 + random.nextInt(900000));
        return Optional.of(new Balance(clientId, accountNumber, currency, available, ledger, REFERENCE_DATE));
    }

    public Optional<List<Movement>> findMovements(String clientId, int limit) {
        if (!exists(clientId)) {
            return Optional.empty();
        }
        Random random = randomFor(clientId, 3);
        int count = Math.min(Math.max(limit, 0), 50);
        List<Movement> movements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean credit = random.nextInt(4) == 0;
            BigDecimal amount = money(random, 2_000_000);
            movements.add(new Movement(
                    "MV-" + clientId.substring(1) + "-" + (i + 1),
                    REFERENCE_DATE.minusDays(i),
                    pick(random, MERCHANTS),
                    credit ? amount : amount.negate(),
                    credit ? "CREDIT" : "DEBIT"));
        }
        return Optional.of(movements);
    }

    private static Random randomFor(String clientId, int salt) {
        return new Random(31L * clientId.hashCode() + salt);
    }

    private static String pick(Random random, String[] values) {
        return values[random.nextInt(values.length)];
    }

    private static BigDecimal money(Random random, int max) {
        return BigDecimal.valueOf(random.nextDouble() * max).setScale(2, RoundingMode.HALF_UP);
    }

    public record Client(String clientId, String fullName, String segment, String documentNumberMasked,
                         LocalDate customerSince) {
    }

    public record Balance(String clientId, String accountNumber, String currency, BigDecimal availableBalance,
                          BigDecimal ledgerBalance, LocalDate asOf) {
    }

    public record Movement(String movementId, LocalDate bookingDate, String description, BigDecimal amount,
                           String type) {
    }
}
