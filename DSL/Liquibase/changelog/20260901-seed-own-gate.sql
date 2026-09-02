--liquibase formatted sql

--changeset efti:seed-own-gate context:dev splitStatements:false
-- Dev/CI only: register this gate's own row (EU-EE) pointing at the local eDelivery
-- service, so gate-to-gate tests (mock-gate.http) and GET /gates/own resolve.
-- Cert = code/certs/own.crt (friendlyName EU-EE), the one edelivery loads from own.p12.
INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, status)
SELECT 'EU-EE', 'EE', 'http://edelivery:8081',
E'-----BEGIN CERTIFICATE-----\nMIIDATCCAemgAwIBAgIUB+wp45qwsgT0vhrdKm7e0LtM1SQwDQYJKoZIhvcNAQEL\nBQAwEDEOMAwGA1UEAwwFRVUtRUUwHhcNMjYwODI1MTQzMjE3WhcNMjcwODI1MTQz\nMjE3WjAQMQ4wDAYDVQQDDAVFVS1FRTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC\nAQoCggEBAKjlsmU2txVTCqvdJYhitJZ27Rd3DPHw/65puwMhRvFS+8SP5jaX3gpC\ne59EZzEab6IS7vK4bfHcL3Nc8986QnKm1kkse74Ou5X8k4EUf2cbeDNJJZ4TZ7Lk\nBTTPCsM5IR2BelmoLCoR+0PygefUwQVF1bZagfuQP/Q3gdQ/EuvqSHDEsceuNtIt\n3y20TKlCjQqNu4uuDpIYb3Jj99z0yoXwdU70/A//7Cz9Gb8WQVoMCrRsEI60k6yO\nqLrEXw2bw2hjYFrwCSCIvC2MHXKoZNvb5e35QZgdmC2e3JV4/85yZsBJhYlobFMR\niq17R0EWR0uJHQE5cmommxRjuAulKJ8CAwEAAaNTMFEwHQYDVR0OBBYEFHwdpyXY\nXQoqggPuCttLNLsDFXOXMB8GA1UdIwQYMBaAFHwdpyXYXQoqggPuCttLNLsDFXOX\nMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAIACmHyOFBqoglic\n9kA99HRGzlRou6BIOBOjmscJOfaKZDsgp0gKhy1jdcobH+HvKWbMLE+gtOb+pFXC\nKafAfs3WssIiRU8eu/Vb1EbXR45kDW/OxL05H0StJsFspP7UtZSh5u+YrtQJw1T6\nJC/ZuARicSMrOtQpEGK3TcCMH8j+qJv6gLmzgf6RkFCQBM967GKo8LwArOyFFkhQ\nZXDJ5hI34jqON91kqmfXfCgx8eGGR3XxYz5pM5MluD0Zy2JOCGu2GLBvHoKvS/6C\n/8uXBeR2cXcgGLpv6KmEmGXDvzHTXDsbccHjvOi9JVi29J0Af9C0/Cy766UEFSOC\np2CFSAM=\n-----END CERTIFICATE-----\n',
       'ONLINE'::gate_status
WHERE NOT EXISTS (SELECT 1 FROM gates WHERE id = 'EU-EE');

--rollback DELETE FROM gates WHERE id = 'EU-EE';

--changeset efti:seed-mock-gate context:dev splitStatements:false
INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, status)
SELECT DISTINCT ON (id)'EU-MOCK', country_code, e_delivery_url, e_delivery_cert, status
FROM gates WHERE id = 'EU-EE' ORDER BY id, created_at DESC;

--rollback DELETE FROM gates WHERE id = 'EU-MOCK';
