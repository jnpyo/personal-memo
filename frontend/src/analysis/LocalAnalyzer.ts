export type LocalAnalysisInput={memoId:string;memoRevision:number;content:string;baseInstant:string;timeZone:string};
export interface LocalAnalyzer{analyze(input:LocalAnalysisInput):Promise<unknown>}
export class FakeLocalAnalyzer implements LocalAnalyzer{async analyze(input:LocalAnalysisInput){return{...input,analyzerVersion:'fake-v1'}}}
