# Andmete teisenduste kirjeldused

> **v2.0 spetsifikatsioon:** [`../../specs/data-transformations.md`](../../specs/data-transformations.md) — täielik XML→SQL→JSON→SOAP teisenduste kirjeldus konkreetsete XPath-ide ja JAXB kaardistustega.

Käesolev sektsioon kirjeldab Eesti eFTI värava (Gate) siseseid ja
välistasandi andmevoogusid ning nendega kaasnevaid teisendusprotsesse.
Värava peamine roll on toimida vahendajana, mis tagab andmete kooskõla
ja liikumise erinevate osapoolte vahel, säilitades andmete tähendusliku terviklikkuse.

## Transformatsioonistsenaariumid (Transformation Scenarios)
Andmete teisendamine toimub eFTI väravas kahes peamises kasutusjuhus.

- eFTI platvorm saadab identifikaatorite andmestiku väravasse.
See sisaldab andmestiku unikaalset identifikaatorit (UUID) ja seotud
otsingutunnuseid (näiteks auto numbrimärk). Väravas salvestatakse need andmed maha
andmebaasis asuvasse identifikaatorite registrisse.

- Pädev asutus esitab päringu läbi REST-liidese (JSON),
mis teisendatakse väravaüleseks päringuks ja vajadusel suunatakse teise liikmesriigi väravasse.
Kui edastatakse päring teise liikmesriigi väravasse läbi eDelivery 
tuleb algne päring muuta XML kujule, mis vastab eDeliverys edastatavale standard formaadile.
XML-i kuju kirjeldavad XSD skeemad, mis on kättesaadavad xsd kaustas.


## Allikas ja sihtkoht
| Stsenaarium                                                                            | Allikas           | Sihtkoht            | Protokoll / vorming                     |
| -------------------------------------------------------------------------------------- | ----------------- | ------------------- | --------------------------------------- |
| Platvorm registreerib eFTI andmekogumi identifikaatorid (metadata + access info)       | eFTI platvorm     | eFTI värav          | REST (JSON/XML) või eDelivery AS4 (XML) |
| Platvorm uuendab või tühistab identifikaatoreid                                        | eFTI platvorm     | eFTI värav          | REST (JSON/XML) või eDelivery AS4 (XML) |
| Pädev asutus teeb päringu identifikaatorite leidmiseks (metadata lookup)               | Pädev asutus      | eFTI värav          | X-Road REST (JSON)                      |
| Värav tagastab leitud identifikaatorid ja metaandmed                                   | eFTI värav        | Pädev asutus        | X-Road REST (JSON)                      |
| Väravas ei leita vastavaid identifikaatoreid ning päring edastatakse välisele väravale | eFTI värav        | Väline eFTI värav   | eDelivery AS4 (XML)                     |
| Väline värav töötleb päringu ja tagastab tulemused                                     | Väline eFTI värav | eFTI värav          | eDelivery AS4 (XML)                     |
| Värav agregeerib tulemused (vajadusel mitmest väravast)                                | eFTI värav        | Pädev asutus        | X-Road REST (JSON)                      |
| Pädev asutus esitab detailandmete päringu (data request)                               | Pädev asutus      | eFTI värav          | X-Road REST (JSON)                      |
| Värav suunab detailandmete päringu õigesse eFTI platvormi                              | eFTI värav        | eFTI platvorm       | REST (JSON/XML) või eDelivery AS4 (XML) |
| eFTI platvorm kontrollib ligipääsuõigusi ja autentimist                                | eFTI platvorm     | eFTI platvorm       | (sisemine protsess)                     |
| eFTI platvorm tagastab andmekogumi või viite (data + access endpoint)                  | eFTI platvorm     | eFTI värav          | REST (JSON/XML) või eDelivery AS4 (XML) |
| Värav edastab andmed pädevale asutusele                                                | eFTI värav        | Pädev asutus        | X-Road REST (JSON/XML)                  |
| Pädev asutus teeb järelpäringu (follow-up / additional data)                           | Pädev asutus      | eFTI värav          | X-Road REST (JSON)                      |
| Värav edastab järelpäringu platvormile                                                 | eFTI värav        | eFTI platvorm       | REST (JSON/XML) või eDelivery AS4 (XML) |

## Teisendusreeglid
Teisendusreeglid tagavad, et andmed vastavad eFTI ühisele andmekogumile.

- Värav peab suutma konverteerida siseriiklikult eelistatud JSON vormingu
(vastavalt KeMITi profiilile) liiduüleseks XML vorminguks, 
mis on kohustuslik väravatevahelises suhtluses vastavalt regulatsioonile 2024/1942 sissejuhatus 16.
- Iga päring ja vastus peab olema kaardistatud vastavalt määruse 2024/2024 
lisades toodud andmeelementidele (nt eFTI39 maanteetranspordi jaoks).
- Värav peab suutma koostada ja valideerida unikaalset identifitseerimislinki,
mis koosneb värava ID-st, platvormi ID-st ja andmestiku UUID-st. värav peab olema võimeline
teostama otsingut kasutades UIL-i teiste väravate vastu.
- Igale väljuvale päringule lisatakse automaatselt kontrolljälg
ja marsruutimise info (requestId).