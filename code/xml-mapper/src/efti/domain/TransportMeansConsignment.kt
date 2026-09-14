package efti.domain

import efti.subsets.CountryCode
import java.time.Instant
import java.util.*

/**
 * One row of core `authority/search` output, as far as the transport-means projection cares.
 * Tolerant by construction: klite's JSON parser drops unknown keys (createFrom reads only
 * constructor params), so both shapes core search can return bind to this class —
 *  - raw local `get_consignments.sql` rows (local hit; extra keys `rowId`, `xml`, seq arrays)
 *  - remote [ConsignmentRow]s from `search/response-to-json` (broadcast; extra keys `xml`, seq
 *    arrays, no `createdAt`)
 * The dropped keys are exactly what the curated projection excludes (ADR-007 variant A): the
 * identifier `xml` blob and the equipment sequence numbers.
 */
data class TransportMeansRow(
  val datasetId: UUID,
  val platformId: PlatformId,
  val gateId: GateId,
  val mainTransportId: String? = null,
  val mainTransportType: String? = null,
  val transportRegCountry: CountryCode? = null,
  val transportMode: Mode? = null,
  val dangerousGoods: DangerousGoods? = null,
  val acceptanceDate: Instant? = null,
  val acceptanceCountry: CountryCode? = null,
  val deliveryDate: Instant? = null,
  val deliveryCountry: CountryCode? = null,
  val loadingDate: Instant? = null,
  val loadingCountry: CountryCode? = null,
  val unloadingDate: Instant? = null,
  val unloadingCountry: CountryCode? = null,
  val usedEquipmentIds: List<String>? = null,
  val usedEquipmentCategories: List<String>? = null,
  val usedEquipmentCountries: List<CountryCode>? = null,
  val carriedEquipmentIds: List<String>? = null,
  val carriedEquipmentCategories: List<String>? = null,
  val status: String? = null,
  /** Local rows only — remote gates do not disclose their registration time. */
  val createdAt: Instant? = null,
)

/**
 * The curated identifier-level projection `POST /xroad/v1/transport-means` returns — the same field
 * contract as `get_consignments_by_transport_means.sql` (`scope: local`), so `scope: allgates`
 * keeps the shape promise of ADR-007 variant A. No dataset content, no identifier XML blob.
 */
data class TransportMeansConsignment(
  val uil: UIL,
  val mainTransportId: String?,
  val mainTransportType: String?,
  val transportRegCountry: CountryCode?,
  val transportMode: Mode?,
  val dangerousGoods: DangerousGoods?,
  val acceptanceDate: Instant?,
  val acceptanceCountry: CountryCode?,
  val deliveryDate: Instant?,
  val deliveryCountry: CountryCode?,
  val loadingDate: Instant?,
  val loadingCountry: CountryCode?,
  val unloadingDate: Instant?,
  val unloadingCountry: CountryCode?,
  val usedEquipmentIds: List<String>?,
  val usedEquipmentCategories: List<String>?,
  val usedEquipmentCountries: List<CountryCode>?,
  val carriedEquipmentIds: List<String>?,
  val carriedEquipmentCategories: List<String>?,
  val status: String?,
  val createdAt: Instant?,
) {
  constructor(row: TransportMeansRow): this(
    UIL(row.platformId, row.datasetId, row.gateId),
    row.mainTransportId,
    row.mainTransportType,
    row.transportRegCountry,
    row.transportMode,
    row.dangerousGoods,
    row.acceptanceDate,
    row.acceptanceCountry,
    row.deliveryDate,
    row.deliveryCountry,
    row.loadingDate,
    row.loadingCountry,
    row.unloadingDate,
    row.unloadingCountry,
    row.usedEquipmentIds,
    row.usedEquipmentCategories,
    row.usedEquipmentCountries,
    row.carriedEquipmentIds,
    row.carriedEquipmentCategories,
    row.status,
    row.createdAt,
  )
}
