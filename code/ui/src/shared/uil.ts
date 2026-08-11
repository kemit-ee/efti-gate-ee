import type {UIL} from 'src/api/types'

export function combineUIL(uil: UIL) {
  return [uil.gateId, uil.platformId, uil.datasetId].map(encodeURIComponent).join('/')
}

export function parseUIL(uil: string): UIL {
  const [gateId, platformId, datasetId] = uil.split('/').map(decodeURIComponent)
  return {gateId, platformId, datasetId}
}
