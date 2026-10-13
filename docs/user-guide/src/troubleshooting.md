# Veaolukorrad ja head tavad

## Sisselogimine suunab tagasi autentimisse

Kontrolli, kas sinu isikukood on vaates **Kasutajad** registreeritud. Kui kasutaja on olemas, palu teisel administraatoril kontrollida, kas token on tühistatud, ning proovi uuesti autentida.

## Värav või platvorm ei ole nimekirjas või on KEELATUD

Väravate, platvormide ja asutuste registrit haldusliidesest muuta ei saa. Kontrolli, kas kirje on repositooriumi kaustas `registry/` olemas ja kas olek on õige, ning pöördu tehnilise meeskonna poole. Muudatus jõuab väravasse alles uue versiooni paigaldamisega.

## Partnervärav ei vasta

1. Ava värava **Detailid** ja kontrolli eDelivery URL-i ja sertifikaati.
2. Veendu partneriga, et nende teenus on kättesaadav ja et neil on Eesti värava kehtiv sertifikaat.
3. Edasta vea aeg ja partneri värava ID tehnilisele toele.

## API võti läks kaduma või lekkis

Võtit ei saa haldusliidesest vaadata ega uuesti genereerida. Teavita tehnilist meeskonda: uus võti lisatakse registrisse, värav paigaldatakse uuesti ja võti edastatakse platvormi operaatorile turvaliselt. Vana võti lakkab kehtimast uue versiooni paigaldamisel.

## Turvaline kasutamine

- Logi jagatud arvutis alati välja.
- Hoia API võtmeid ja sertifikaatide privaatvõtmeid saladuste haldussüsteemis.
- Kontrolli enne kasutaja või saadetise kustutamist kirje ID-d.
- Integratsiooni ajutiseks peatamiseks palu registris seada olekuks **KEELATUD** — konfiguratsioon säilib.
- Anna pädevale asutusele ainult vajalikud andmete alamhulgad.

## Lisainfo

- [eFTI Gate GitHubis](https://github.com/kemit-ee/efti-gate-ee)
- [Tehnilised spetsifikatsioonid](https://github.com/kemit-ee/efti-gate-ee/tree/dev/docs/specs)
- [Arhitektuuridokumentatsioon](https://github.com/kemit-ee/efti-gate-ee/tree/dev/docs/architecture)

