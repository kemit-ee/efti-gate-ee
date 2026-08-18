export function combineUIL(gateId: string, platformId: string, datasetId: string) {
  return [gateId, platformId, datasetId].map(encodeURIComponent).join('/')
}