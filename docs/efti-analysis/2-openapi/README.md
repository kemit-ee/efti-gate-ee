# Openapi skeemad

> **v2.0 spetsifikatsioon:** [`../../specs/openapi.yaml`](../../specs/openapi.yaml) — kõik kolm API-t on konsolideeritud üheks OpenAPI 3.0 failiks.
> Muudatused v1 suhtes: RFC 7807 veaformaat, paginatsioon, audit log endpointid, `GET /health/ready`.

Siin kaustas on 3 openapi skeemat (v1 originaal), mis kirjeldavad värava ning
väljaspool asuvate osapoolte REST põhist suhtlust.

api-on-gate.json kirjeldab APIt mis asub värava peal, seda kutsuvad pädevad asutused kui ka platvormid, 
juhul kui nad on liidestunud REST liidesega. NB! pädevate asutuste REST liides on mõeldud kasutamiseks
läbi X-TEE.

api-on-platform.json kirjeldab milline näeb välja REST api eFTI platvormi peal.
Selle API külge liidestub värav otse, ning selle abil on  võimalik pädevatel asutustel 
küsida eFTI andmeid. NB! kuigi hange ese on ainult eFTI värav, on siiski oluline luua ka
platvormi näidislahendus, et erinevaid integratsioone testida. 

admin-api-on-gate.json kirjeldab eFTI väraval asuvat apit selle haldamiseks. 
Admin API on oluline selleks et oleks võimaliukult lihtne hallata väravaga seotud ühendusi.