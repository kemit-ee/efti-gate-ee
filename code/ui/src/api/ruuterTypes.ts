export enum Status {
  ONLINE = 'ONLINE',
  OFFLINE = 'OFFLINE',
  DISABLED = 'DISABLED',
  DELETED = 'DELETED'
}
export enum SubsetCode {
  EU01 = 'EU01',
  EU02 = 'EU02',
  EU03 = 'EU03',
  EU04 = 'EU04',
  EU05 = 'EU05',
  EU06 = 'EU06',
  EU07 = 'EU07'
}
export enum CountryCode {EU = 'EU', AL = 'AL', AD = 'AD', AT = 'AT', BY = 'BY', BE = 'BE', BA = 'BA', BG = 'BG', HR = 'HR', CY = 'CY', CZ = 'CZ', DK = 'DK', EE = 'EE', FI = 'FI', FR = 'FR', DE = 'DE', EL = 'EL', HU = 'HU', IS = 'IS', IE = 'IE', IT = 'IT', LV = 'LV', LI = 'LI', LT = 'LT', LU = 'LU', MT = 'MT', MD = 'MD', MC = 'MC', ME = 'ME', NL = 'NL', MK = 'MK', NO = 'NO', PL = 'PL', PT = 'PT', RO = 'RO', RU = 'RU', SM = 'SM', RS = 'RS', SK = 'SK', SI = 'SI', ES = 'ES', SE = 'SE', CH = 'CH', UA = 'UA', GB = 'GB', VA = 'VA', DO = 'DO', AF = 'AF', AX = 'AX', DZ = 'DZ', AS = 'AS', AO = 'AO', AI = 'AI', AQ = 'AQ', AG = 'AG', AR = 'AR', AM = 'AM', AW = 'AW', AU = 'AU', AZ = 'AZ', BS = 'BS', BH = 'BH', BD = 'BD', BB = 'BB', BZ = 'BZ', BJ = 'BJ', BM = 'BM', BT = 'BT', BO = 'BO', BQ = 'BQ', BW = 'BW', BV = 'BV', BR = 'BR', IO = 'IO', BN = 'BN', BF = 'BF', BI = 'BI', CV = 'CV', KH = 'KH', CM = 'CM', CA = 'CA', KY = 'KY', CF = 'CF', TD = 'TD', CL = 'CL', CN = 'CN', CX = 'CX', CC = 'CC', CO = 'CO', KM = 'KM', CD = 'CD', CG = 'CG', CK = 'CK', CR = 'CR', CI = 'CI', CU = 'CU', CW = 'CW', DJ = 'DJ', DM = 'DM', EC = 'EC', EG = 'EG', SV = 'SV', GQ = 'GQ', ER = 'ER', SZ = 'SZ', ET = 'ET', FK = 'FK', FO = 'FO', FJ = 'FJ', GF = 'GF', PF = 'PF', TF = 'TF', GA = 'GA', GM = 'GM', GE = 'GE', GH = 'GH', GI = 'GI', GR = 'GR', GL = 'GL', GD = 'GD', GP = 'GP', GU = 'GU', GT = 'GT', GG = 'GG', GN = 'GN', GW = 'GW', GY = 'GY', HT = 'HT', HM = 'HM', HN = 'HN', HK = 'HK', IN = 'IN', ID = 'ID', IR = 'IR', IQ = 'IQ', IM = 'IM', IL = 'IL', JM = 'JM', JP = 'JP', JE = 'JE', JO = 'JO', KZ = 'KZ', KE = 'KE', KI = 'KI', KP = 'KP', KR = 'KR', KW = 'KW', KG = 'KG', LA = 'LA', LB = 'LB', LS = 'LS', LR = 'LR', LY = 'LY', MO = 'MO', MG = 'MG', MW = 'MW', MY = 'MY', MV = 'MV', ML = 'ML', MH = 'MH', MQ = 'MQ', MR = 'MR', MU = 'MU', YT = 'YT', MX = 'MX', FM = 'FM', MN = 'MN', MS = 'MS', MA = 'MA', MZ = 'MZ', MM = 'MM', NA = 'NA', NR = 'NR', NP = 'NP', NC = 'NC', NZ = 'NZ', NI = 'NI', NE = 'NE', NG = 'NG', NU = 'NU', NF = 'NF', MP = 'MP', OM = 'OM', PK = 'PK', PW = 'PW', PS = 'PS', PA = 'PA', PG = 'PG', PY = 'PY', PE = 'PE', PH = 'PH', PN = 'PN', PR = 'PR', QA = 'QA', RE = 'RE', RW = 'RW', BL = 'BL', SH = 'SH', KN = 'KN', LC = 'LC', MF = 'MF', PM = 'PM', VC = 'VC', WS = 'WS', ST = 'ST', SA = 'SA', SN = 'SN', SC = 'SC', SL = 'SL', SG = 'SG', SX = 'SX', SB = 'SB', SO = 'SO', ZA = 'ZA', GS = 'GS', SS = 'SS', LK = 'LK', SD = 'SD', SR = 'SR', SJ = 'SJ', SY = 'SY', TW = 'TW', TJ = 'TJ', TZ = 'TZ', TH = 'TH', TL = 'TL', TG = 'TG', TK = 'TK', TO = 'TO', TT = 'TT', TN = 'TN', TR = 'TR', TM = 'TM', TC = 'TC', TV = 'TV', UG = 'UG', AE = 'AE', UM = 'UM', US = 'US', UY = 'UY', UZ = 'UZ', VU = 'VU', VE = 'VE', VN = 'VN', VG = 'VG', VI = 'VI', WF = 'WF', EH = 'EH', YE = 'YE', ZM = 'ZM', ZW = 'ZW'}
export type Subset = string

export interface Gate {
  id: string
  rowId: string
  countryCode: CountryCode
  eDeliveryUrl: string
  eDeliveryCert?: string
  tlsCert?: string
  status: Status
  lastPingAt?: string
  createdAt: string
}
export interface GateRequest {
  id: string
  countryCode: string
  eDeliveryUrl: string
  eDeliveryCert?: string
  tlsCert?: string
  status?: Status
}

export interface Platform {
  id: string
  baseUrl: string
  headers?: Record<string, string>
  eDeliveryCert?: string
  tlsCert?: string
  status: Status
  apiKeyHint?: string
  apiKeyGeneratedAt?: string
  hasApiKey?: boolean
  createdAt: string
}

export interface PlatformApiKey {
  id: string
  apiKey: string
  apiKeyHint: string
  apiKeyGeneratedAt: string
}

export interface PlatformRequest {
  id: string
  baseUrl: string
  headers?: Record<string, string>
  eDeliveryCert?: string
  tlsCert?: string
  status?: Status
}

export interface Authority {
  id: string
  name: string
  registryCode: string
  subsets: SubsetCode[]
  status: Status
  createdAt: string
}

export interface AuthorityRequest {
  id: string
  name: string
  registryCode: string
  subsets: SubsetCode[]
  status?: Status
}

export interface User {
  id: string
  taraSub: string
  name: string
  isAdmin: boolean
  isAuthority: boolean
  createdAt: string
  tokenRevokedAt?: string
}

export interface CreateUserRequest {
  taraSub: string
  name: string
  isAdmin: boolean
  isAuthority: boolean
}

export interface UpdateUserRequest {
  name: string
  taraSub: string
  isAdmin: boolean
  isAuthority: boolean
}

export enum ConsignmentStatus {
  ACTIVE = 'ACTIVE',
  INACTIVE = 'INACTIVE',
  DELETED = 'DELETED',
}

export interface Consignment {
  datasetId: string
  platformId: string
  gateId: string
  status: ConsignmentStatus
  xml: string
  dangerousGoods: string
  createdAt: string
  usedEquipmentIds: string[]
  carriedEquipmentIds: string[]
  mainTransportId: string
}

export interface TaraLoginResponse {
  authorization_url: string
  provider: string
  state: string
}