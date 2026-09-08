--liquibase formatted sql

--changeset efti:013-seed-mock-platform context:dev
INSERT INTO platforms (id, base_url, headers) VALUES
  ('mock', 'http://ruuter:8086/mock-platform', '{"X-Api-Key": "mock-secret-key"}'::jsonb);

--rollback DELETE FROM platforms WHERE id = 'mock';

--changeset efti:mock-edelivery context:dev splitStatements:false
INSERT INTO platforms (id, base_url, e_delivery_cert) VALUES
  ('mock-edelivery', 'http://edelivery:8081/services/msh',
   E'-----BEGIN CERTIFICATE-----\nMIIDATCCAemgAwIBAgIUB+wp45qwsgT0vhrdKm7e0LtM1SQwDQYJKoZIhvcNAQEL\nBQAwEDEOMAwGA1UEAwwFRVUtRUUwHhcNMjYwODI1MTQzMjE3WhcNMjcwODI1MTQz\nMjE3WjAQMQ4wDAYDVQQDDAVFVS1FRTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC\nAQoCggEBAKjlsmU2txVTCqvdJYhitJZ27Rd3DPHw/65puwMhRvFS+8SP5jaX3gpC\ne59EZzEab6IS7vK4bfHcL3Nc8986QnKm1kkse74Ou5X8k4EUf2cbeDNJJZ4TZ7Lk\nBTTPCsM5IR2BelmoLCoR+0PygefUwQVF1bZagfuQP/Q3gdQ/EuvqSHDEsceuNtIt\n3y20TKlCjQqNu4uuDpIYb3Jj99z0yoXwdU70/A//7Cz9Gb8WQVoMCrRsEI60k6yO\nqLrEXw2bw2hjYFrwCSCIvC2MHXKoZNvb5e35QZgdmC2e3JV4/85yZsBJhYlobFMR\niq17R0EWR0uJHQE5cmommxRjuAulKJ8CAwEAAaNTMFEwHQYDVR0OBBYEFHwdpyXY\nXQoqggPuCttLNLsDFXOXMB8GA1UdIwQYMBaAFHwdpyXYXQoqggPuCttLNLsDFXOX\nMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAIACmHyOFBqoglic\n9kA99HRGzlRou6BIOBOjmscJOfaKZDsgp0gKhy1jdcobH+HvKWbMLE+gtOb+pFXC\nKafAfs3WssIiRU8eu/Vb1EbXR45kDW/OxL05H0StJsFspP7UtZSh5u+YrtQJw1T6\nJC/ZuARicSMrOtQpEGK3TcCMH8j+qJv6gLmzgf6RkFCQBM967GKo8LwArOyFFkhQ\nZXDJ5hI34jqON91kqmfXfCgx8eGGR3XxYz5pM5MluD0Zy2JOCGu2GLBvHoKvS/6C\n/8uXBeR2cXcgGLpv6KmEmGXDvzHTXDsbccHjvOi9JVi29J0Af9C0/Cy766UEFSOC\np2CFSAM=\n-----END CERTIFICATE-----\n');