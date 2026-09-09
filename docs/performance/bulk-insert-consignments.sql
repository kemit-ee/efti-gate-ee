-- Bulk insert 1M consignments for load testing
-- Usage: psql -d efti -f bulk-insert-consignments.sql

\timing on

DO $$
DECLARE
  batch_size constant int := 10000;
  total constant int := 1000000;
  max_batch int := total / batch_size;
BEGIN
  FOR b IN 0..max_batch - 1 LOOP
    INSERT INTO consignments (
      dataset_id, platform_id, gate_id, xml, status,
      transport_mode, acceptance_date, acceptance_country,
      delivery_date, delivery_country, dangerous_goods,
      main_transport_id, main_transport_type, transport_reg_country,
      loading_date, loading_country, unloading_date, unloading_country,
      used_equipment_ids, used_equipment_categories, used_equipment_countries, used_equipment_seq,
      carried_equipment_ids, carried_equipment_categories, carried_equipment_seq
    )
    SELECT
      gen_random_uuid(),
      'mock',
      'EE',
      '<ParameterIDSetCriteria xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <CarrierAcceptanceDateParameterScope>
    <SpecifiedDateTime>
      <udt:DateTimeString format="102">20210924</udt:DateTimeString>
    </SpecifiedDateTime>
  </CarrierAcceptanceDateParameterScope>
  <CarrierAcceptanceCountryParameterScope>
    <CountryID>DE</CountryID>
  </CarrierAcceptanceCountryParameterScope>
  <DeliveryCountryParameterScope>
    <CountryID>EE</CountryID>
  </DeliveryCountryParameterScope>
  <DangerousGoodsIndicationCodeParameterScope>
    <DangerousGoodsIndicationParameterCode>2</DangerousGoodsIndicationParameterCode>
  </DangerousGoodsIndicationCodeParameterScope>
  <MainCarriageTransportMeansIDParameterScope>
    <ID>BULK-' || i || '</ID>
  </MainCarriageTransportMeansIDParameterScope>
  <MainCarriageModeCodeParameterScope>
    <TransportModeParameterCode>1</TransportModeParameterCode>
  </MainCarriageModeCodeParameterScope>
  <MainCarriageTransportMeansTypeCodeParameterScope>
    <TransportMeansParameterCode>1513</TransportMeansParameterCode>
  </MainCarriageTransportMeansTypeCodeParameterScope>
  <TransportMeansRegistrationCountryParameterScope>
    <CountryID>DE</CountryID>
  </TransportMeansRegistrationCountryParameterScope>
  <MainCarriageLoadingDateParameterScope>
    <SpecifiedDateTime>
      <udt:DateTimeString format="102">20210924</udt:DateTimeString>
    </SpecifiedDateTime>
  </MainCarriageLoadingDateParameterScope>
  <MainCarriageLoadingCountryParameterScope>
    <CountryID>FI</CountryID>
  </MainCarriageLoadingCountryParameterScope>
  <MainCarriageUnloadingDateParameterScope>
    <SpecifiedDateTime>
      <udt:DateTimeString format="102">20210924</udt:DateTimeString>
    </SpecifiedDateTime>
  </MainCarriageUnloadingDateParameterScope>
  <MainCarriageUnloadingCountryParameterScope>
    <CountryID>AE</CountryID>
  </MainCarriageUnloadingCountryParameterScope>
  <UsedTransportEquipmentIDParameterScope>
    <ID>TE-' || (i * 2) || '</ID>
    <ID>TE-' || (i * 2 + 1) || '</ID>
  </UsedTransportEquipmentIDParameterScope>
  <UsedTransportEquipmentCategoryCodeParameterScope>
    <TransportEquipmentCategoryParameterCode>T10</TransportEquipmentCategoryParameterCode>
    <TransportEquipmentCategoryParameterCode>T10</TransportEquipmentCategoryParameterCode>
  </UsedTransportEquipmentCategoryCodeParameterScope>
  <UsedTransportEquipmentRegistrationCountryParameterScope>
    <CountryID>DE</CountryID>
    <CountryID>DE</CountryID>
  </UsedTransportEquipmentRegistrationCountryParameterScope>
  <UsedTransportEquipmentSequenceNumberParameterScope>
    <SequenceNumeric>1</SequenceNumeric>
    <SequenceNumeric>2</SequenceNumeric>
  </UsedTransportEquipmentSequenceNumberParameterScope>
  <CarriedTransportEquipmentIDParameterScope>
    <ID>CT-' || (i * 2 + 2) || '</ID>
    <ID>CT-' || (i * 2 + 3) || '</ID>
  </CarriedTransportEquipmentIDParameterScope>
  <CarriedTransportEquipmentCategoryCodeParameterScope>
    <TransportEquipmentCategoryParameterCode>BPR</TransportEquipmentCategoryParameterCode>
    <TransportEquipmentCategoryParameterCode>BPR</TransportEquipmentCategoryParameterCode>
  </CarriedTransportEquipmentCategoryCodeParameterScope>
  <CarriedTransportEquipmentSequenceNumberParameterScope>
    <SequenceNumeric>3</SequenceNumeric>
    <SequenceNumeric>4</SequenceNumeric>
  </CarriedTransportEquipmentSequenceNumberParameterScope>
</ParameterIDSetCriteria>',
      'ACTIVE',
      '1',
      '2021-09-24 00:00:00+00',
      'DE',
      '2021-09-24 00:00:00+00',
      'EE',
      '2',
      'BULK-' || i,
      '1513',
      'DE',
      '2021-09-24 00:00:00+00',
      'FI',
      '2021-09-24 00:00:00+00',
      'AE',
      ARRAY['TE-' || (i * 2), 'TE-' || (i * 2 + 1)],
      ARRAY['T10', 'T10'],
      ARRAY['DE', 'DE'],
      ARRAY[1, 2],
      ARRAY['CT-' || (i * 2 + 2), 'CT-' || (i * 2 + 3)],
      ARRAY['BPR', 'BPR'],
      ARRAY[3, 4]
    FROM generate_series(b * batch_size + 1, (b + 1) * batch_size) AS i;

    IF b % 10 = 0 THEN
      RAISE NOTICE 'inserted % / %', (b + 1) * batch_size, total;
    END IF;
  END LOOP;

  RAISE NOTICE 'done: % consignments inserted', total;
END $$;
