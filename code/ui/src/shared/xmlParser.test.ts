import {describe, expect, it} from 'vitest'
import {parseXmlToJson} from './xmlParser'

describe('xmlParser', () => {
  const sampleXml = `<?xml version="1.0" encoding="UTF-8"?><consignment xmlns="http://efti.eu/v1/consignment/common">
    <applicableServiceCharge>
        <appliedAmount currencyId="MXN">1.30</appliedAmount>
        <calculationBasisCode>gytg</calculationBasisCode>
        <calculationBasisPrice>
            <basisQuantity>4071</basisQuantity>
            <categoryTypeCode>igep</categoryTypeCode>
            <unitAmount currencyId="XBA">3.96</unitAmount>
        </calculationBasisPrice>
        <id>kfcj</id>
        <payingPartyRoleCode>fonh</payingPartyRoleCode>
        <paymentArrangementCode>pqhl</paymentArrangementCode>
    </applicableServiceCharge>
    <associatedDocument>
        <attachedBinaryFile>
            <id schemeAgencyId="hpeqkq">uansjw</id>
            <includedBinaryObject>dnFzaXN0</includedBinaryObject>
        </attachedBinaryFile>
        <attachedBinaryObject>eWlyamJo</attachedBinaryObject>
        <contractualClause>
            <contentText>xnkv</contentText>
        </contractualClause>
        <formattedIssueDateTime formatId="205">203206171735+0000</formattedIssueDateTime>
        <id schemeAgencyId="ntqdbw">kbbg</id>
        <issueLocation>
            <geographicalCoordinates>
                <latitude unitId="njsh">2.56</latitude>
                <longitude unitId="vtzh">3.13</longitude>
            </geographicalCoordinates>
            <id schemeAgencyId="okykpz">dgjtlw</id>
            <name>qidx</name>
            <postalAddress>
                <additionalStreetName>belm</additionalStreetName>
                <buildingNumber>xtyx</buildingNumber>
                <cityName>pqdu</cityName>
                <countryCode>NG</countryCode>
                <countrySubDivisionName>vnph</countrySubDivisionName>
                <departmentName>rckq</departmentName>
                <postOfficeBox>kjjz</postOfficeBox>
                <postcode>qpex</postcode>
                <streetName>fraw</streetName>
            </postalAddress>
        </issueLocation>
    </associatedDocument>
    <mainCarriageTransportMovement>
        <dangerousGoodsIndicator>true</dangerousGoodsIndicator>
    </mainCarriageTransportMovement>
</consignment>`

  it('parses complex XML to JSON', () => {
    const json = parseXmlToJson(sampleXml)
    expect(json).toBeDefined()
    expect(json.applicableServiceCharge).toBeDefined()
    expect(json.applicableServiceCharge.appliedAmount.value).toBe('1.30')
    expect(json.applicableServiceCharge.appliedAmount.currencyId).toBe('MXN')
    expect(json.associatedDocument.attachedBinaryFile.id.value).toBe('uansjw')
    expect(json.associatedDocument.attachedBinaryFile.id.schemeAgencyId).toBe('hpeqkq')
    expect(json.mainCarriageTransportMovement.dangerousGoodsIndicator).toBe('true')
  })

  it('handles empty XML', () => {
    expect(parseXmlToJson('')).toBeNull()
  })

  it('handles invalid XML', () => {
    expect(parseXmlToJson('<invalid>')).toBeNull()
  })
})
