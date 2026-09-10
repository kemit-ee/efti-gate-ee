# Veaolukorrad ja head tavad

## Sisselogimine suunab tagasi autentimisse

Kontrolli, kas sinu isikukood on vaates **Kasutajad** registreeritud. Kui kasutaja on olemas, palu teisel administraatoril kontrollida, kas token on tühistatud, ning proovi uuesti autentida.

## Värav või platvorm on OFFLINE

1. Kontrolli URL-i ja sertifikaate.
2. Veendu, et partnerteenus on kättesaadav.
3. Vajuta **Ping** ja vaata, kas olek muutub.
4. Kui kontroll ebaõnnestub, jäta kirje alles ning edasta vea aeg ja partneri ID tehnilisele toele.

## Sertifikaati ei saa salvestada

Sertifikaat peab olema PEM-vormingus ja sisaldama plokki `BEGIN CERTIFICATE`. Kopeeri kogu sertifikaat koos algus- ja lõpureaga.

## API võti läks kaduma

Olemasolevat täielikku võtit ei saa uuesti vaadata. Genereeri platvormile uus võti, talleta see turvaliselt ja uuenda platvormi konfiguratsioon. Vana võti lakkab kohe töötamast.

## Turvaline kasutamine

- Logi jagatud arvutis alati välja.
- Hoia API võtmeid ja sertifikaatide privaatvõtmeid saladuste haldussüsteemis.
- Kontrolli enne kustutamist kirje ID-d.
- Kasuta **KEELATUD** olekut, kui integratsioon tuleb ajutiselt peatada, kuid konfiguratsioon peab säilima.
- Anna pädevale asutusele ainult vajalikud andmete alamhulgad.

## Lisainfo

- [eFTI Gate GitHubis](https://github.com/kemit-ee/efti-gate-ee)
- [Tehnilised spetsifikatsioonid](https://github.com/kemit-ee/efti-gate-ee/tree/dev/docs/specs)
- [Arhitektuuridokumentatsioon](https://github.com/kemit-ee/efti-gate-ee/tree/dev/docs/architecture)

