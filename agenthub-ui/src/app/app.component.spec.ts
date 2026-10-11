import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the navigation', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const nav = (fixture.nativeElement as HTMLElement).querySelectorAll('.nav-link');
    expect(nav.length).toBe(6);
    expect(nav[1].getAttribute('href')).toBe('/diagnostics');
  });

  it('links the active project to the projects page', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const link = (fixture.nativeElement as HTMLElement).querySelector('.active-target');
    expect(link?.getAttribute('href')).toBe('/targets');
  });
});
