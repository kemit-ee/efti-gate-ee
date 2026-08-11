export type Id<T extends Entity<T>> = string & {_of?: T}
export type Entity<T extends Entity<T>> = {id: Id<T>}

// class efti.GateIdentifiersResponse
export interface GateIdentifiersResponse {consignments?: Array<ConsignmentXml>; failure?: string; gateId: string; responseTimeMs: number}
// class efti.authorities.Authority
export interface Authority {countryCode: CountryCode; id: string; subsets: Array<Subset>}
// class efti.domain.Status
export enum Status {ONLINE = 'ONLINE', OFFLINE = 'OFFLINE', DISABLED = 'DISABLED'}
// class efti.gates.Gate
export interface Gate {countryCode: CountryCode; eDeliveryCert: string; eDeliveryUrl: URI; id: string; isDisabled: boolean; isFast: boolean; isOnline: boolean; status: Status; tlsCert?: string; xsdVersion: XsdVersion}
// class efti.platforms.Platform
export interface Platform {baseUrl: URI; eDeliveryCert?: string; eDeliveryUrl?: URI; headers: Record<string, string>; id: string; isDisabled: boolean; isOnline: boolean; status: Status; supportsSubsetting: boolean; tlsCert?: string; xsdVersion: XsdVersion}
// class users.Role
export enum Role {ADMIN = 'ADMIN', GATE = 'GATE', PLATFORM = 'PLATFORM', AUTHORITY = 'AUTHORITY'}
// class users.User
export interface User {email?: Email; id: string; isAdmin: boolean; isSuperAdmin: boolean; name: string; roles: Partial<Record<Role, Array<string>>>; subsets?: Array<Subset>}
// class efti.subsets.CountryCode
export enum CountryCode {EU = 'EU', AL = 'AL', AD = 'AD', AT = 'AT', BY = 'BY', BE = 'BE', BA = 'BA', BG = 'BG', HR = 'HR', CY = 'CY', CZ = 'CZ', DK = 'DK', EE = 'EE', FI = 'FI', FR = 'FR', DE = 'DE', EL = 'EL', HU = 'HU', IS = 'IS', IE = 'IE', IT = 'IT', LV = 'LV', LI = 'LI', LT = 'LT', LU = 'LU', MT = 'MT', MD = 'MD', MC = 'MC', ME = 'ME', NL = 'NL', MK = 'MK', NO = 'NO', PL = 'PL', PT = 'PT', RO = 'RO', RU = 'RU', SM = 'SM', RS = 'RS', SK = 'SK', SI = 'SI', ES = 'ES', SE = 'SE', CH = 'CH', UA = 'UA', GB = 'GB', VA = 'VA', DO = 'DO', AF = 'AF', AX = 'AX', DZ = 'DZ', AS = 'AS', AO = 'AO', AI = 'AI', AQ = 'AQ', AG = 'AG', AR = 'AR', AM = 'AM', AW = 'AW', AU = 'AU', AZ = 'AZ', BS = 'BS', BH = 'BH', BD = 'BD', BB = 'BB', BZ = 'BZ', BJ = 'BJ', BM = 'BM', BT = 'BT', BO = 'BO', BQ = 'BQ', BW = 'BW', BV = 'BV', BR = 'BR', IO = 'IO', BN = 'BN', BF = 'BF', BI = 'BI', CV = 'CV', KH = 'KH', CM = 'CM', CA = 'CA', KY = 'KY', CF = 'CF', TD = 'TD', CL = 'CL', CN = 'CN', CX = 'CX', CC = 'CC', CO = 'CO', KM = 'KM', CD = 'CD', CG = 'CG', CK = 'CK', CR = 'CR', CI = 'CI', CU = 'CU', CW = 'CW', DJ = 'DJ', DM = 'DM', EC = 'EC', EG = 'EG', SV = 'SV', GQ = 'GQ', ER = 'ER', SZ = 'SZ', ET = 'ET', FK = 'FK', FO = 'FO', FJ = 'FJ', GF = 'GF', PF = 'PF', TF = 'TF', GA = 'GA', GM = 'GM', GE = 'GE', GH = 'GH', GI = 'GI', GR = 'GR', GL = 'GL', GD = 'GD', GP = 'GP', GU = 'GU', GT = 'GT', GG = 'GG', GN = 'GN', GW = 'GW', GY = 'GY', HT = 'HT', HM = 'HM', HN = 'HN', HK = 'HK', IN = 'IN', ID = 'ID', IR = 'IR', IQ = 'IQ', IM = 'IM', IL = 'IL', JM = 'JM', JP = 'JP', JE = 'JE', JO = 'JO', KZ = 'KZ', KE = 'KE', KI = 'KI', KP = 'KP', KR = 'KR', KW = 'KW', KG = 'KG', LA = 'LA', LB = 'LB', LS = 'LS', LR = 'LR', LY = 'LY', MO = 'MO', MG = 'MG', MW = 'MW', MY = 'MY', MV = 'MV', ML = 'ML', MH = 'MH', MQ = 'MQ', MR = 'MR', MU = 'MU', YT = 'YT', MX = 'MX', FM = 'FM', MN = 'MN', MS = 'MS', MA = 'MA', MZ = 'MZ', MM = 'MM', NA = 'NA', NR = 'NR', NP = 'NP', NC = 'NC', NZ = 'NZ', NI = 'NI', NE = 'NE', NG = 'NG', NU = 'NU', NF = 'NF', MP = 'MP', OM = 'OM', PK = 'PK', PW = 'PW', PS = 'PS', PA = 'PA', PG = 'PG', PY = 'PY', PE = 'PE', PH = 'PH', PN = 'PN', PR = 'PR', QA = 'QA', RE = 'RE', RW = 'RW', BL = 'BL', SH = 'SH', KN = 'KN', LC = 'LC', MF = 'MF', PM = 'PM', VC = 'VC', WS = 'WS', ST = 'ST', SA = 'SA', SN = 'SN', SC = 'SC', SL = 'SL', SG = 'SG', SX = 'SX', SB = 'SB', SO = 'SO', ZA = 'ZA', GS = 'GS', SS = 'SS', LK = 'LK', SD = 'SD', SR = 'SR', SJ = 'SJ', SY = 'SY', TW = 'TW', TJ = 'TJ', TZ = 'TZ', TH = 'TH', TL = 'TL', TG = 'TG', TK = 'TK', TO = 'TO', TT = 'TT', TN = 'TN', TR = 'TR', TM = 'TM', TC = 'TC', TV = 'TV', UG = 'UG', AE = 'AE', UM = 'UM', US = 'US', UY = 'UY', UZ = 'UZ', VU = 'VU', VE = 'VE', VN = 'VN', VG = 'VG', VI = 'VI', WF = 'WF', EH = 'EH', YE = 'YE', ZM = 'ZM', ZW = 'ZW'}
// class efti.xml.IdentifiersQuery
export interface IdentifiersQuery {dangerousGoodsIndicator?: boolean; forceBroadcast: boolean; identifier: IdentifiersQueryId; modeCode?: string; registrationCountryCode?: CountryCode; requestId: string}
// class efti.domain.Consignment
export interface Consignment {acceptedAt?: Instant; createdAt: Instant; dangerousGoods?: string; deliveredAt?: Instant; identifiers: Array<Identifier>; mode?: string; parameters?: ParameterIDSetCriteria; uil: UIL; updatedAt: Instant; xml: string}

// class efti.xml.ConsignmentXml
export interface ConsignmentXml {carrierAcceptanceDateTime?: DateTime; deliveryDateTime?: DateTime; identifierCountryOfOrigin?: CountryCode; mainCarriageTransportMovement: Array<MainCarriageTransportMovement>; uil?: UIL; usedTransportEquipment: Array<UsedTransportEquipment>}
// class efti.domain.XsdVersion
export enum XsdVersion {V0_9 = 'V0_9', FTI = 'FTI'}
// class efti.xml.IdentifiersQuery$Id
export interface IdentifiersQueryId {type?: string; types?: Array<IdentifierType>; value: string}
// class efti.domain.Identifier
export interface Identifier {countryCode?: CountryCode; id: string; type: IdentifierType}
// class efti.xml.fti.ParameterIDSetCriteria
export interface ParameterIDSetCriteria {acceptanceCountry?: CountryCode; acceptanceDate?: DateTimeString; carriedEquipmentCategories: Array<string>; carriedEquipmentIds: Array<string>; carriedEquipmentSeq: Array<string>; dangerousGoods?: string; deliveryCountry?: CountryCode; deliveryDate?: DateTimeString; loadingCountry?: CountryCode; loadingDate?: DateTimeString; mainTransportId?: string; mainTransportType?: string; transportMode?: string; transportRegCountry?: CountryCode; unloadingCountry?: CountryCode; unloadingDate?: DateTimeString; usedEquipmentCategories: Array<string>; usedEquipmentCountries: Array<CountryCode>; usedEquipmentIds: Array<string>; usedEquipmentSeq: Array<string>}
// class efti.domain.UIL
export interface UIL {datasetId: string; gateId: string; platformId: string}
// class efti.xml.DateTime
export interface DateTime {formatId?: string; instant: Instant; value: string}
// class efti.xml.MainCarriageTransportMovement
export interface MainCarriageTransportMovement {dangerousGoodsIndicator?: boolean; modeCode?: string; usedTransportMeans?: UsedTransportMeans}
// class efti.xml.UsedTransportEquipment
export interface UsedTransportEquipment {carriedTransportEquipment: Array<CarriedTransportEquipment>; categoryCode?: string; id: XmlId; registrationCountry?: CountryCode; sequenceNumber?: number}
// class efti.domain.Identifier$Type
export enum IdentifierType {means = 'means', equipment = 'equipment', carried = 'carried'}
// class efti.xml.fti.DateTimeString
export interface DateTimeString {formatId: string; instant: Instant; value: string}
// class efti.xml.UsedTransportMeans
export interface UsedTransportMeans {id: XmlId; registrationCountry?: CountryCode}
// class efti.xml.CarriedTransportEquipment
export interface CarriedTransportEquipment {id: XmlId; sequenceNumber?: number}
// class efti.xml.XmlId
export interface XmlId {schemeAgencyId?: string; value: string}

// java.time.Instant
export type Instant = `${number}-${number}-${number}T${number}:${number}:${number}Z`
// java.net.URI
export type URI = `${string}://${string}`
// klite.Email
export type Email = `${string}@${string}`
// efti.subsets.Subset
export type Subset = string
